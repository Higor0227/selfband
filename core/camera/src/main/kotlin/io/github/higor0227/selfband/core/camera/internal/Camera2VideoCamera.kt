// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.core.camera.internal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.higor0227.selfband.core.camera.CameraState
import io.github.higor0227.selfband.core.camera.PixelSize
import io.github.higor0227.selfband.core.camera.VideoCamera
import io.github.higor0227.selfband.core.camera.VideoConfig
import io.github.higor0227.selfband.core.camera.VideoRecording
import io.github.higor0227.selfband.core.model.CameraLens
import io.github.higor0227.selfband.core.model.LOG_TAG
import io.github.higor0227.selfband.core.model.VideoClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Camera2 + MediaCodec implementation (SPEC 3.1 fallback, chosen by the owner on
 * 2026-11-08 because CameraX does not expose the first frame's timestamp).
 *
 * One capture session holds the preview surface and a persistent encoder surface; the
 * repeating request targets the encoder only while recording, so the session never has to
 * be rebuilt to start or stop a take.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
internal class Camera2VideoCamera @Inject constructor(
    @ApplicationContext private val context: Context,
) : VideoCamera {

    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("SelfbandCamera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val control = Dispatchers.IO.limitedParallelism(1)
    // The dispatcher alone does not serialize: operations suspend while waiting for camera
    // callbacks, and another one could start in between (e.g. two session builds).
    private val lock = Mutex()

    private suspend fun <T> serial(block: suspend () -> T): T = lock.withLock { withContext(control) { block() } }

    private val _state = MutableStateFlow(CameraState())
    override val state: StateFlow<CameraState> = _state.asStateFlow()

    private data class Selected(
        val id: String,
        val lens: CameraLens,
        val sensorOrientation: Int,
        val videoSize: Size,
        val previewSize: Size,
        val fpsRange: Range<Int>,
        val frameRate: Int,
        val afContinuousVideo: Boolean,
        val declaredRealtime: Boolean,
    )

    private var selected: Selected? = null
    private var config = VideoConfig()
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    /** The preview surface the current session was configured with. */
    private var sessionPreview: Surface? = null
    private var encoderSurface: Surface? = null
    private var encoder: VideoEncoder? = null
    private var recordingFile: File? = null
    private var recordingRotation = 0
    private val clock = ClockDetector()
    private val exposures = ExposureLog()

    override fun availableLenses(): List<CameraLens> =
        runCatching { manager.cameraIdList.mapNotNull { lensOf(it) }.distinct() }.getOrDefault(emptyList())

    private fun lensOf(id: String): CameraLens? {
        val c = manager.getCameraCharacteristics(id)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return null
        if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in caps) return null
        return when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_BACK -> CameraLens.BACK
            CameraMetadata.LENS_FACING_FRONT -> CameraLens.FRONT
            CameraMetadata.LENS_FACING_EXTERNAL -> CameraLens.EXTERNAL
            else -> null
        }
    }

    override suspend fun open(lens: CameraLens, config: VideoConfig): Boolean = serial {
        closeLocked()
        this@Camera2VideoCamera.config = config
        val pick = runCatching { select(lens, config) }.getOrNull()
        if (pick == null) {
            _state.value = CameraState(error = "No camera facing $lens")
            return@serial false
        }
        val dev = openDevice(pick.id)
        if (dev == null) {
            _state.value = CameraState(error = "Could not open camera ${pick.id}")
            return@serial false
        }
        device = dev
        selected = pick
        clock.reset(pick.declaredRealtime)
        val surface = MediaCodec.createPersistentInputSurface()
        encoderSurface = surface
        val enc = VideoEncoder(surface, pick.videoSize.width, pick.videoSize.height, pick.frameRate, config.bitRate)
        if (!enc.prepare()) {
            closeLocked()
            _state.value = CameraState(error = "Video encoder unavailable")
            return@serial false
        }
        encoder = enc
        _state.value = CameraState(
            isOpen = true,
            lens = pick.lens,
            videoSize = PixelSize(pick.videoSize.width, pick.videoSize.height),
            previewSize = PixelSize(pick.previewSize.width, pick.previewSize.height),
            sensorOrientation = pick.sensorOrientation,
            frameRate = pick.frameRate,
        )
        Log.i(
            LOG_TAG,
            "Camera ${pick.id} ($lens) open: video ${pick.videoSize} preview ${pick.previewSize} " +
                "fps ${pick.fpsRange} sensor ${pick.sensorOrientation}° realtime=${pick.declaredRealtime}",
        )
        buildSession()
        true
    }

    override suspend fun setPreviewSurface(surface: Surface?): Unit = serial {
        if (surface == previewSurface) return@serial
        val old = previewSurface
        previewSurface = surface
        if (device == null) return@serial
        if (!_state.value.recording) {
            buildSession()
        } else if (old != null && old == sessionPreview) {
            // The view changed mid-take: keep encoding, stop targeting the old surface.
            // The new one is picked up when the take ends (see stopRecording).
            setRepeating(recording = true)
        }
    }

    override suspend fun startRecording(file: File, displayRotationDegrees: Int): Boolean = serial {
        val pick = selected ?: return@serial false
        val enc = encoder ?: return@serial false
        if (session == null) return@serial false
        val rotation = if (pick.lens == CameraLens.FRONT) {
            (pick.sensorOrientation + displayRotationDegrees) % 360
        } else {
            (pick.sensorOrientation - displayRotationDegrees + 360) % 360
        }
        if (!enc.start(file, rotation)) return@serial false
        exposures.clear()
        recordingFile = file
        recordingRotation = rotation
        if (!setRepeating(recording = true)) {
            enc.finish()
            enc.prepare()
            return@serial false
        }
        _state.update { it.copy(recording = true, framesRecorded = 0) }
        true
    }

    override suspend fun stopRecording(): VideoRecording? = serial {
        val pick = selected ?: return@serial null
        val enc = encoder ?: return@serial null
        val file = recordingFile ?: return@serial null
        recordingFile = null
        setRepeating(recording = false) // stop feeding the encoder
        val stats = enc.finish()
        enc.prepare() // ready for the next take
        _state.update { it.copy(recording = false) }
        // The preview surface changed mid-take: rebuild the session with the new one.
        if (previewSurface != sessionPreview) buildSession()
        if (stats == null) {
            file.delete()
            return@serial null
        }
        val frames = stats.frames
        val durationUs = if (frames > 1) {
            (stats.lastPtsUs - stats.firstPtsUs) * frames / (frames - 1)
        } else 1_000_000L / pick.frameRate
        val firstNs = stats.firstPtsUs * 1_000
        val bootOffset = SystemClock.elapsedRealtimeNanos() - System.nanoTime()
        // Which clock is the encoder's first PTS in? The camera may hand the encoder its
        // sensor timestamps as they are, or converted from BOOTTIME to MONOTONIC (the
        // platform default before timestamp bases existed). Match it to a capture result.
        val direct = exposures.find(firstNs)
        val converted = if (direct == null) exposures.find(firstNs + bootOffset) else null
        val ptsClock = when {
            direct != null -> clock.clock
            converted != null -> VideoClock.MONOTONIC
            else -> plausibleClock(firstNs, bootOffset, durationUs)
        }
        val (exposure, skew) = direct ?: converted ?: (0L to 0L)
        val center = firstNs + exposure / 2 + skew / 2
        val monotonic = when (ptsClock) {
            VideoClock.MONOTONIC -> center
            VideoClock.BOOTTIME -> center - bootOffset
            VideoClock.UNKNOWN -> null
        }
        Log.i(
            LOG_TAG,
            "Video finished: $frames frames, ${durationUs / 1000} ms, first pts ${stats.firstPtsUs} us " +
                "(${if (direct != null) "sensor base" else if (converted != null) "converted to monotonic" else "unmatched"}), " +
                "exposure ${exposure / 1000} us, skew ${skew / 1000} us, sensor clock ${clock.clock}, pts clock $ptsClock",
        )
        VideoRecording(
            file = file,
            lens = pick.lens,
            width = pick.videoSize.width,
            height = pick.videoSize.height,
            rotationDegrees = recordingRotation,
            frameRate = pick.frameRate,
            frameCount = frames,
            durationUs = durationUs,
            firstFrameMonotonicNanos = monotonic,
            clock = ptsClock,
        )
    }

    /** Without a matching capture result: the clock in which the first frame is a recent past. */
    private fun plausibleClock(firstNs: Long, bootOffset: Long, durationUs: Long): VideoClock {
        val window = 0..(durationUs * 1_000 + 10_000_000_000L)
        val mono = System.nanoTime()
        return when {
            mono - firstNs in window -> VideoClock.MONOTONIC
            mono + bootOffset - firstNs in window -> VideoClock.BOOTTIME
            else -> VideoClock.UNKNOWN
        }
    }

    override suspend fun close(): Unit = serial { closeLocked() }

    // --- internals (control thread) ------------------------------------------------------

    private fun closeLocked() {
        runCatching { session?.close() }
        session = null
        sessionPreview = null
        runCatching { device?.close() }
        device = null
        encoder?.release()
        encoder = null
        encoderSurface?.release()
        encoderSurface = null
        recordingFile = null
        selected = null
        if (_state.value.isOpen) Log.i(LOG_TAG, "Camera closed")
        _state.value = CameraState()
    }

    private fun select(lens: CameraLens, config: VideoConfig): Selected? {
        val id = manager.cameraIdList.firstOrNull { lensOf(it) == lens } ?: return null
        val c = manager.getCameraCharacteristics(id)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val targetAspect = config.width.toDouble() / config.height
        fun best(sizes: Array<Size>?, maxW: Int, maxH: Int): Size? = sizes.orEmpty()
            .filter { it.width <= maxW && it.height <= maxH }
            .sortedWith(
                compareBy<Size> { abs(it.width.toDouble() / it.height - targetAspect) > 0.01 }
                    .thenByDescending { it.width * it.height },
            )
            .firstOrNull()
        val video = best(map.getOutputSizes(MediaCodec::class.java), config.width, config.height) ?: return null
        val preview = best(map.getOutputSizes(SurfaceTexture::class.java), 1920, 1080) ?: video
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        val range = ranges.firstOrNull { it.lower == config.frameRate && it.upper == config.frameRate }
            ?: ranges.filter { it.upper == config.frameRate }.maxByOrNull { it.lower }
            ?: ranges.maxByOrNull { it.upper }
            ?: Range(config.frameRate, config.frameRate)
        val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        return Selected(
            id = id,
            lens = lens,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            videoSize = video,
            previewSize = preview,
            fpsRange = range,
            frameRate = range.upper,
            afContinuousVideo = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes,
            declaredRealtime = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
        )
    }

    @SuppressLint("MissingPermission") // the caller checks the CAMERA permission
    private suspend fun openDevice(id: String): CameraDevice? = suspendCancellableCoroutine { cont ->
        try {
            manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (cont.isActive) cont.resume(camera) else camera.close()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cont.isActive) cont.resume(null)
                    onDeviceLost("Camera disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cont.isActive) cont.resume(null)
                    onDeviceLost("Camera error $error")
                }
            })
        } catch (e: Exception) {
            Log.e(LOG_TAG, "openCamera failed", e)
            if (cont.isActive) cont.resume(null)
        }
    }

    private fun onDeviceLost(reason: String) {
        Log.w(LOG_TAG, reason)
        _state.update { it.copy(isOpen = false, error = reason) }
    }

    private suspend fun buildSession() {
        val dev = device ?: return
        runCatching { session?.close() }
        session = null
        sessionPreview = previewSurface
        val outputs = buildList {
            previewSurface?.let { add(OutputConfiguration(it)) }
            encoderSurface?.let { surface ->
                add(OutputConfiguration(surface).apply {
                    // Keep the encoder's PTS equal to SENSOR_TIMESTAMP, so the first frame can be
                    // matched to its capture result. Older versions are handled in stopRecording.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        timestampBase = OutputConfiguration.TIMESTAMP_BASE_SENSOR
                    }
                })
            }
        }
        if (outputs.isEmpty()) return
        val s = suspendCancellableCoroutine { cont ->
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cont.isActive) cont.resume(session) else session.close()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(LOG_TAG, "Camera session configuration failed")
                    if (cont.isActive) cont.resume(null)
                }
            }
            try {
                dev.createCaptureSession(
                    SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback),
                )
            } catch (e: Exception) {
                Log.e(LOG_TAG, "createCaptureSession failed", e)
                if (cont.isActive) cont.resume(null)
            }
        }
        session = s
        if (s == null) {
            _state.update { it.copy(error = "Camera session failed") }
            return
        }
        setRepeating(recording = false)
    }

    private fun setRepeating(recording: Boolean): Boolean {
        val dev = device ?: return false
        val s = session ?: return false
        val pick = selected ?: return false
        return runCatching {
            val targets = buildList {
                previewSurface?.takeIf { it == sessionPreview }?.let { add(it) }
                if (recording) encoderSurface?.let { add(it) }
            }
            if (targets.isEmpty()) {
                s.stopRepeating()
                return true
            }
            // The same template while idle and recording, so exposure does not jump at start.
            val request = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                targets.forEach { addTarget(it) }
                if (recording) setTag(RECORDING_TAG)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, pick.fpsRange)
                if (pick.afContinuousVideo) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }
            }.build()
            s.setRepeatingRequest(request, captureCallback, handler)
            true
        }.getOrElse {
            Log.e(LOG_TAG, "setRepeatingRequest failed", it)
            false
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val ts = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: return
            if (clock.observe(ts, System.nanoTime(), SystemClock.elapsedRealtimeNanos())) {
                _state.update { it.copy(clock = clock.clock) }
            }
            val exposure = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val skew = result.get(android.hardware.camera2.CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) ?: 0L
            if (request.tag === RECORDING_TAG) exposures.add(ts, exposure, skew)
            if (_state.value.recording) {
                val n = encoder?.frames ?: 0
                if (n != _state.value.framesRecorded) _state.update { it.copy(framesRecorded = n) }
            }
        }
    }
}

/**
 * Sensor timestamp, exposure and rolling-shutter skew (ns) of the first frames captured for
 * the current take. Kept from the start (not a ring), so the first frame is never lost.
 */
internal class ExposureLog(private val capacity: Int = 120) {
    private val ts = LongArray(capacity)
    private val exposure = LongArray(capacity)
    private val skew = LongArray(capacity)
    private var count = 0

    @Synchronized fun clear() {
        count = 0
    }

    @Synchronized fun add(timestamp: Long, exposureNs: Long, skewNs: Long) {
        if (count == capacity) return
        ts[count] = timestamp
        exposure[count] = exposureNs
        skew[count] = skewNs
        count++
    }

    /** Exposure and skew of the frame with this timestamp (within 1 ms). */
    @Synchronized fun find(timestamp: Long): Pair<Long, Long>? {
        var best = -1
        var bestDelta = Long.MAX_VALUE
        for (i in 0 until count) {
            val d = abs(ts[i] - timestamp)
            if (d < bestDelta) {
                bestDelta = d
                best = i
            }
        }
        return if (best >= 0 && bestDelta <= 1_000_000) exposure[best] to skew[best] else null
    }
}

private val RECORDING_TAG = Any()
