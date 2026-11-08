// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.layout

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.higor0227.selfband.core.model.BackgroundKind
import io.github.higor0227.selfband.core.model.CanvasLayout
import io.github.higor0227.selfband.core.model.CanvasSettings
import io.github.higor0227.selfband.core.model.Layer
import io.github.higor0227.selfband.core.model.LayerEditing
import io.github.higor0227.selfband.core.model.LayerEditing.Edge
import io.github.higor0227.selfband.core.model.NormRect
import io.github.higor0227.selfband.core.model.Project
import io.github.higor0227.selfband.core.model.displayAspect
import io.github.higor0227.selfband.core.model.value
import io.github.higor0227.selfband.core.project.ProjectRepository
import io.github.higor0227.selfband.core.video.CanvasPreview
import io.github.higor0227.selfband.core.video.CanvasPreviewFactory
import io.github.higor0227.selfband.core.video.CanvasScenes
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class EditMode { MOVE, CROP }

/** What a touch grabbed. */
sealed interface DragTarget {
    data object None : DragTarget
    data class Body(val trackId: String) : DragTarget
    /** Scale handle; [corner]: 0 = top left, 1 = top right, 2 = bottom right, 3 = bottom left. */
    data class Corner(val trackId: String, val corner: Int) : DragTarget
    data class CropHandle(val trackId: String, val edges: Set<Edge>) : DragTarget
}

data class LayoutUiState(
    val project: Project? = null,
    /** Canvas being edited (the stored one until the first edit). */
    val canvas: CanvasSettings? = null,
    /** Layers as drawn, bottom to top. */
    val layers: List<Layer> = emptyList(),
    val selectedTrackId: String? = null,
    val mode: EditMode = EditMode.MOVE,
    val snap: Boolean = true,
    val guides: List<LayerEditing.Guide> = emptyList(),
    val playheadFrames: Long = 0,
    val importingImage: Boolean = false,
    val imageImportFailed: Boolean = false,
) {
    val selected: Layer? get() = layers.firstOrNull { it.trackId == selectedTrackId }
    val canvasAspect: Float get() = project?.aspectRatio?.value ?: (9f / 16f)
}

private data class Local(
    val draft: CanvasSettings? = null,
    val selectedTrackId: String? = null,
    val mode: EditMode = EditMode.MOVE,
    val snap: Boolean = true,
    val guides: List<LayerEditing.Guide> = emptyList(),
    val playheadFrames: Long = 0,
    val importingImage: Boolean = false,
    val imageImportFailed: Boolean = false,
)

/**
 * Layout editor (SPEC 2.6): select, move, scale and crop layers, order, opacity, corners,
 * border, visibility, snap and align. The canvas shows the frame at the playhead (no audio
 * here). Edits go to a working copy shown live and are saved at the end of each gesture.
 */
@HiltViewModel
class LayoutEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val repository: ProjectRepository,
    canvasFactory: CanvasPreviewFactory,
) : ViewModel() {

    private val projectId: String = checkNotNull(savedStateHandle[ARG_PROJECT_ID])
    private val local = MutableStateFlow(Local())
    @Volatile private var playhead = 0L

    val canvasPreview: CanvasPreview = canvasFactory.create()

    val uiState: StateFlow<LayoutUiState> = combine(repository.observe(projectId), local) { project, l ->
        val canvas = l.draft ?: project?.canvas
        LayoutUiState(
            project = project,
            canvas = canvas,
            layers = if (project != null && canvas != null) {
                CanvasLayout.effectiveLayers(project.copy(canvas = canvas))
            } else {
                emptyList()
            },
            selectedTrackId = l.selectedTrackId,
            mode = l.mode,
            snap = l.snap,
            guides = l.guides,
            playheadFrames = l.playheadFrames,
            importingImage = l.importingImage,
            imageImportFailed = l.imageImportFailed,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LayoutUiState())

    /** Layer as it was when the current gesture started. */
    private var origin: Layer? = null

    init {
        canvasPreview.setPosition { playhead.toDouble() }
        viewModelScope.launch {
            combine(repository.observe(projectId).filterNotNull(), local.map { it.draft }.distinctUntilChanged()) { p, draft ->
                CanvasScenes.build(
                    p,
                    { trackId, name -> repository.takeAudioFile(p.id, trackId, name) },
                    { name -> repository.projectMediaFile(p.id, name) },
                    draft ?: p.canvas,
                )
            }.distinctUntilChanged().collect { canvasPreview.setScene(it) }
        }
    }

    fun onStart() = canvasPreview.setPaused(false)
    fun onStop() = canvasPreview.setPaused(true)

    override fun onCleared() {
        canvasPreview.release()
    }

    fun setPlayhead(frames: Long) {
        val max = uiState.value.project?.lengthFrames ?: 0L
        playhead = frames.coerceIn(0L, max)
        local.update { it.copy(playheadFrames = playhead) }
    }

    fun select(trackId: String?) = local.update { it.copy(selectedTrackId = trackId) }
    fun setMode(mode: EditMode) = local.update { it.copy(mode = mode) }
    fun setSnap(on: Boolean) = local.update { it.copy(snap = on) }

    // --- gestures ------------------------------------------------------------------------

    /** Topmost visible layer at ([x], [y]) (canvas units). */
    private fun layerAt(x: Float, y: Float): Layer? =
        uiState.value.layers.lastOrNull { it.visible && it.frame.contains(x, y) }

    /**
     * What a touch at ([x], [y]) grabs: a handle of the selected layer (within [rx] × [ry]
     * canvas units), its body, or nothing yet.
     */
    fun hitTest(x: Float, y: Float, rx: Float, ry: Float): DragTarget {
        val state = uiState.value
        val sel = state.selected ?: return DragTarget.None
        val f = sel.frame
        fun near(px: Float, py: Float) = kotlin.math.abs(x - px) <= rx && kotlin.math.abs(y - py) <= ry
        val corners = listOf(f.x to f.y, f.right to f.y, f.right to f.bottom, f.x to f.bottom)
        if (state.mode == EditMode.CROP) {
            val cornerEdges = listOf(
                setOf(Edge.LEFT, Edge.TOP), setOf(Edge.RIGHT, Edge.TOP),
                setOf(Edge.RIGHT, Edge.BOTTOM), setOf(Edge.LEFT, Edge.BOTTOM),
            )
            corners.forEachIndexed { i, (cx, cy) -> if (near(cx, cy)) return DragTarget.CropHandle(sel.trackId, cornerEdges[i]) }
            val mx = f.x + f.width / 2f
            val my = f.y + f.height / 2f
            if (near(f.x, my)) return DragTarget.CropHandle(sel.trackId, setOf(Edge.LEFT))
            if (near(f.right, my)) return DragTarget.CropHandle(sel.trackId, setOf(Edge.RIGHT))
            if (near(mx, f.y)) return DragTarget.CropHandle(sel.trackId, setOf(Edge.TOP))
            if (near(mx, f.bottom)) return DragTarget.CropHandle(sel.trackId, setOf(Edge.BOTTOM))
        } else {
            corners.forEachIndexed { i, (cx, cy) -> if (near(cx, cy)) return DragTarget.Corner(sel.trackId, i) }
        }
        return if (f.contains(x, y)) DragTarget.Body(sel.trackId) else DragTarget.None
    }

    /** A drag started on nothing: grab the layer under the finger, if any. */
    fun grabAt(x: Float, y: Float): DragTarget {
        val layer = layerAt(x, y) ?: return DragTarget.None
        select(layer.trackId)
        return DragTarget.Body(layer.trackId)
    }

    fun tap(x: Float, y: Float) = select(layerAt(x, y)?.trackId)

    fun beginGesture(trackId: String?) {
        val id = trackId ?: return
        ensureFree()
        origin = currentLayer(id)
    }

    /**
     * Drag of the grabbed target by ([totalX], [totalY]) since the gesture started (canvas
     * units). [snapX], [snapY]: snap distance in canvas units.
     */
    fun drag(target: DragTarget, totalX: Float, totalY: Float, snapX: Float, snapY: Float) {
        val start = origin ?: return
        val state = uiState.value
        when (target) {
            is DragTarget.Body -> if (state.mode == EditMode.CROP) {
                apply(LayerEditing.panCrop(start, totalX, totalY))
            } else {
                val f = start.frame
                var x = f.x + totalX
                var y = f.y + totalY
                var guides = emptyList<LayerEditing.Guide>()
                if (state.snap) {
                    val others = state.layers.filter { it.visible && it.trackId != start.trackId }.map { it.frame }
                    val s = LayerEditing.snap(x, y, f.width, f.height, others, snapX, snapY)
                    x = s.x
                    y = s.y
                    guides = s.guides
                }
                apply(LayerEditing.moveTo(start, x, y), guides)
            }
            is DragTarget.Corner -> {
                val f = start.frame
                val aspect = state.canvasAspect
                val corners = listOf(f.x to f.y, f.right to f.y, f.right to f.bottom, f.x to f.bottom)
                val (px, py) = corners[(target.corner + 2) % 4]
                val (sx, sy) = corners[target.corner]
                // Work in proportional units (x scaled by the canvas aspect) and project the
                // finger onto the diagonal.
                val vx = (sx - px) * aspect
                val vy = sy - py
                val wx = (sx + totalX - px) * aspect
                val wy = sy + totalY - py
                val factor = (wx * vx + wy * vy) / (vx * vx + vy * vy).coerceAtLeast(1e-6f)
                apply(LayerEditing.scale(start, factor, px, py))
            }
            is DragTarget.CropHandle -> {
                var l = start
                for (edge in target.edges) {
                    l = LayerEditing.cropEdge(l, edge, if (edge == Edge.LEFT || edge == Edge.RIGHT) totalX else totalY)
                }
                apply(l)
            }
            DragTarget.None -> Unit
        }
    }

    /** Pinch by [zoom] (incremental) around ([cx], [cy]). */
    fun pinch(zoom: Float, cx: Float, cy: Float) {
        val id = uiState.value.selectedTrackId ?: return
        val layer = currentLayer(id) ?: return
        apply(
            if (uiState.value.mode == EditMode.CROP) LayerEditing.zoomCrop(layer, zoom)
            else LayerEditing.scale(layer, zoom, cx, cy),
        )
    }

    fun endGesture() {
        origin = null
        local.update { it.copy(guides = emptyList()) }
        commit()
    }

    // --- properties of the selected layer --------------------------------------------------

    /** Applies [transform] to the selected layer; [save] = false while a slider is dragged. */
    fun editSelected(save: Boolean = true, transform: (Layer) -> Layer) {
        val id = uiState.value.selectedTrackId ?: return
        ensureFree()
        val layer = currentLayer(id) ?: return
        apply(transform(layer))
        if (save) commit()
    }

    fun align(align: LayerEditing.Align) = editSelected { LayerEditing.align(it, align) }

    fun resetCrop() {
        val state = uiState.value
        val video = state.project?.tracks?.firstOrNull { it.id == state.selectedTrackId }?.activeTake?.video ?: return
        editSelected { LayerEditing.resetCrop(it, video.displayAspect, state.canvasAspect) }
    }

    fun restack(up: Boolean) {
        val state = uiState.value
        val id = state.selectedTrackId ?: return
        ensureFree()
        val draft = local.value.draft ?: return
        val order = state.project?.tracks?.map { it.id }.orEmpty()
        local.update { it.copy(draft = LayerEditing.restack(draft, id, up, order)) }
        commit()
    }

    // --- background (SPEC 2.6) ------------------------------------------------------------

    fun setBackground(kind: BackgroundKind) = editCanvas { it.copy(background = kind) }

    fun setBackgroundColor(argb: Long) = editCanvas { it.copy(backgroundColor = argb) }

    fun setBlurTrack(trackId: String) = editCanvas { it.copy(background = BackgroundKind.BLUR, backgroundTrackId = trackId) }

    /** Copies the picked picture into the project and shows it as the background. */
    fun importBackgroundImage(uri: Uri) {
        val project = uiState.value.project ?: return
        if (local.value.importingImage) return
        local.update { it.copy(importingImage = true, imageImportFailed = false) }
        viewModelScope.launch {
            val name = "background-${UUID.randomUUID()}.jpg"
            val ok = withContext(Dispatchers.IO) {
                BackgroundImages.import(context, uri, repository.projectMediaFile(project.id, name))
            }
            if (ok) {
                val old = (local.value.draft ?: project.canvas).backgroundImage
                editCanvas { it.copy(background = BackgroundKind.IMAGE, backgroundImage = name) }
                if (old != null && old != name) {
                    withContext(Dispatchers.IO) { repository.projectMediaFile(project.id, old).delete() }
                }
            }
            local.update { it.copy(importingImage = false, imageImportFailed = !ok) }
        }
    }

    /** Canvas-wide change: keeps the layout (no template freeze) and saves. */
    private fun editCanvas(transform: (CanvasSettings) -> CanvasSettings) {
        val project = uiState.value.project ?: return
        local.update { it.copy(draft = transform(it.draft ?: project.canvas)) }
        commit()
    }

    // --- internals -----------------------------------------------------------------------

    /** Before the first edit: freeze the current layout (template → free layout). */
    private fun ensureFree() {
        val state = uiState.value
        val project = state.project ?: return
        val canvas = local.value.draft ?: project.canvas
        val needs = canvas.templateId != null ||
            project.tracks.any { t -> t.activeTake?.video != null && canvas.layers.none { it.trackId == t.id } }
        val frozen = if (needs) LayerEditing.materialize(project.copy(canvas = canvas)) else canvas
        if (frozen != local.value.draft) local.update { it.copy(draft = frozen) }
    }

    private fun currentLayer(trackId: String): Layer? = local.value.draft?.layers?.firstOrNull { it.trackId == trackId }

    private fun apply(layer: Layer, guides: List<LayerEditing.Guide> = emptyList()) {
        val draft = local.value.draft ?: return
        local.update { it.copy(draft = LayerEditing.replace(draft, layer), guides = guides) }
    }

    private val saving = Mutex()

    /** Saves the working copy; the latest one always wins, whatever the order of the saves. */
    private fun commit() {
        viewModelScope.launch {
            saving.withLock {
                val draft = local.value.draft ?: return@withLock
                repository.update(projectId) { it.copy(canvas = draft) }
            }
        }
    }

    companion object {
        const val ARG_PROJECT_ID = "projectId"
    }
}

private fun NormRect.contains(px: Float, py: Float) = px >= x && px <= right && py >= y && py <= bottom
