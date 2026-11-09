// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.feature.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.higor0227.selfband.core.model.AppSettings
import io.github.higor0227.selfband.core.model.AspectRatio
import io.github.higor0227.selfband.core.model.CameraDefaults
import io.github.higor0227.selfband.core.model.CameraLens
import io.github.higor0227.selfband.core.model.ClickSound
import io.github.higor0227.selfband.core.model.ExportDefaults
import io.github.higor0227.selfband.core.model.ExportFolder
import io.github.higor0227.selfband.core.model.MetronomeSettings
import io.github.higor0227.selfband.core.model.Proxies
import io.github.higor0227.selfband.core.model.ProxyMode
import io.github.higor0227.selfband.core.model.ThemeMode
import io.github.higor0227.selfband.core.model.VideoCodec
import io.github.higor0227.selfband.core.model.VideoResolution
import kotlin.math.roundToInt

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(state, onBack, onOpenDiagnostics, viewModel::update, viewModel::setExportFolder)
}

/** App settings (SPEC 2.8). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onExportFolder: (String) -> Unit,
) {
    val settings = state.settings
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section(stringResource(R.string.settings_appearance)) {
                Label(stringResource(R.string.settings_theme))
                Chips(ThemeMode.entries, settings.theme, { stringResource(it.label) }) { theme ->
                    onUpdate { it.copy(theme = theme) }
                }
                Label(stringResource(R.string.settings_language))
                LanguageChips()
            }

            Section(stringResource(R.string.settings_new_projects), stringResource(R.string.settings_new_projects_hint)) {
                Label(stringResource(R.string.settings_aspect))
                Chips(AspectRatio.entries, settings.aspectRatio, { stringResource(it.label) }) { ratio ->
                    onUpdate { it.copy(aspectRatio = ratio) }
                }
                CameraDefaultsFields(settings.camera) { change -> onUpdate { it.copy(camera = change(it.camera)) } }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                MetronomeDefaultsFields(settings.metronome) { change ->
                    onUpdate { it.copy(metronome = change(it.metronome).normalized()) }
                }
            }

            Section(stringResource(R.string.settings_export), stringResource(R.string.settings_export_hint)) {
                ExportDefaultsFields(settings.export) { change -> onUpdate { it.copy(export = change(it.export)) } }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                if (state.loaded) ExportFolderField(settings.exportFolder, onExportFolder)
            }

            Section(stringResource(R.string.settings_proxies), stringResource(R.string.settings_proxies_hint), chips = false) {
                ProxyMode.entries.forEach { mode ->
                    ChoiceRow(
                        title = stringResource(mode.label),
                        subtitle = if (mode == ProxyMode.AUTO) {
                            stringResource(R.string.settings_proxies_auto_hint, Proxies.AUTO_FROM_VIDEOS)
                        } else {
                            null
                        },
                        selected = settings.proxyMode == mode,
                        onClick = { onUpdate { it.copy(proxyMode = mode) } },
                    )
                }
                if (state.proxiesPending > 0) {
                    Text(
                        pluralStringResource(R.plurals.settings_proxies_pending, state.proxiesPending, state.proxiesPending),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            Section(
                stringResource(R.string.settings_export_decoders),
                stringResource(R.string.settings_export_decoders_hint),
                chips = false,
            ) {
                val current = settings.exportDecodersPerPass
                ChoiceRow(
                    title = stringResource(R.string.settings_export_decoders_auto),
                    subtitle = stringResource(R.string.settings_export_decoders_auto_hint),
                    selected = current == null,
                    onClick = { onUpdate { it.copy(exportDecodersPerPass = null) } },
                )
                // A value set elsewhere (not one of the choices) is shown too.
                (AppSettings.DECODERS_PER_PASS_CHOICES + listOfNotNull(current)).distinct().forEach { count ->
                    ChoiceRow(
                        title = count.toString(),
                        subtitle = null,
                        selected = current == count,
                        onClick = { onUpdate { it.copy(exportDecodersPerPass = count) } },
                    )
                }
            }

            Section(stringResource(R.string.settings_diagnostics), chips = false) {
                SwitchRow(
                    stringResource(R.string.settings_verbose_log),
                    stringResource(R.string.settings_verbose_log_hint),
                    settings.verboseLog,
                ) { on -> onUpdate { it.copy(verboseLog = on) } }
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenDiagnostics).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.diagnostics_title), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }
        }
    }
}

@Composable
private fun CameraDefaultsFields(camera: CameraDefaults, onChange: ((CameraDefaults) -> CameraDefaults) -> Unit) {
    Label(stringResource(R.string.settings_camera))
    Chips(listOf(CameraLens.FRONT, CameraLens.BACK), camera.lens, { stringResource(it.label) }) { lens ->
        onChange { it.copy(lens = lens) }
    }
    Label(stringResource(R.string.settings_camera_quality))
    Chips(VideoResolution.entries, camera.resolution, { stringResource(it.label) }) { resolution ->
        onChange { it.copy(resolution = resolution) }
    }
    Chips(CameraDefaults.FRAME_RATES, camera.frameRate, { stringResource(R.string.settings_fps, it) }) { fps ->
        onChange { it.copy(frameRate = fps) }
    }
    Text(
        stringResource(R.string.settings_camera_quality_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    SwitchRow(stringResource(R.string.settings_stabilization), null, camera.stabilization) { on ->
        onChange { it.copy(stabilization = on) }
    }
}

@Composable
private fun MetronomeDefaultsFields(
    metronome: MetronomeSettings,
    onChange: ((MetronomeSettings) -> MetronomeSettings) -> Unit,
) {
    SwitchRow(stringResource(R.string.settings_metronome_on), null, metronome.enabled) { on ->
        onChange { it.copy(enabled = on) }
    }
    var bpmDraft by remember(metronome.bpm) { mutableFloatStateOf(metronome.bpm.roundToInt().toFloat()) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = { onChange { it.copy(bpm = it.bpm.roundToInt() - 1.0) } }) { Text("−") }
        Text(
            stringResource(R.string.settings_bpm, bpmDraft.roundToInt()),
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedButton(onClick = { onChange { it.copy(bpm = it.bpm.roundToInt() + 1.0) } }) { Text("+") }
    }
    Slider(
        value = bpmDraft,
        onValueChange = { bpmDraft = it.roundToInt().toFloat() },
        onValueChangeFinished = { onChange { it.copy(bpm = bpmDraft.toDouble()) } },
        valueRange = MetronomeSettings.MIN_BPM.toFloat()..MetronomeSettings.MAX_BPM.toFloat(),
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Label(stringResource(R.string.settings_time_signature))
    val signatures = (SIGNATURES + (metronome.beatsPerBar to metronome.beatUnit)).distinct()
    Chips(signatures, metronome.beatsPerBar to metronome.beatUnit, { "${it.first}/${it.second}" }) { (beats, unit) ->
        onChange { it.copy(beatsPerBar = beats, beatUnit = unit) }
    }
    Label(stringResource(R.string.settings_count_in))
    Chips((0..MetronomeSettings.MAX_COUNT_IN_BARS).toList(), metronome.countInBars, {
        if (it == 0) stringResource(R.string.settings_count_in_none) else pluralStringResource(R.plurals.settings_bars, it, it)
    }) { bars -> onChange { it.copy(countInBars = bars) } }
    Label(stringResource(R.string.settings_click_sound))
    Chips(ClickSound.entries, metronome.sound, { stringResource(it.label) }) { sound ->
        onChange { it.copy(sound = sound) }
    }
}

@Composable
private fun ExportDefaultsFields(export: ExportDefaults, onChange: ((ExportDefaults) -> ExportDefaults) -> Unit) {
    SwitchRow(stringResource(R.string.settings_export_video), null, export.video) { on -> onChange { it.copy(video = on) } }
    if (export.video) {
        Label(stringResource(R.string.settings_export_codec))
        Chips(VideoCodec.entries, export.codec, { stringResource(it.label) }) { codec -> onChange { it.copy(codec = codec) } }
        Label(stringResource(R.string.settings_export_resolution))
        Chips(ExportDefaults.SHORT_SIDES, export.shortSide, { stringResource(shortSideLabel(it)) }) { side ->
            onChange { it.copy(shortSide = side) }
        }
        Chips(ExportDefaults.FRAME_RATES, export.frameRate, { stringResource(R.string.settings_fps, it) }) { fps ->
            onChange { it.copy(frameRate = fps) }
        }
    }
    SwitchRow(stringResource(R.string.settings_export_mix_wav), null, export.mixWav) { on -> onChange { it.copy(mixWav = on) } }
    SwitchRow(stringResource(R.string.settings_export_stems), null, export.stems) { on -> onChange { it.copy(stems = on) } }
    if (export.mixWav || export.stems) {
        Label(stringResource(R.string.settings_export_wav_format))
        Chips(listOf(false, true), export.wavFloat, {
            stringResource(if (it) R.string.settings_wav_float else R.string.settings_wav_24)
        }) { float -> onChange { it.copy(wavFloat = float) } }
    }
}

@Composable
private fun ExportFolderField(stored: String, onChange: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf(stored) }
    val folder = ExportFolder.sanitize(draft)
    OutlinedTextField(
        value = draft,
        onValueChange = {
            draft = it.take(ExportFolder.MAX_LENGTH)
            onChange(draft)
        },
        label = { Text(stringResource(R.string.settings_export_folder)) },
        supportingText = { Text(stringResource(R.string.settings_export_folder_hint, folder)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

/** Per-app language (SPEC 2.8), kept by the platform; changing it recreates the screen. */
@Composable
private fun LanguageChips() {
    var current by remember { mutableStateOf(AppCompatDelegate.getApplicationLocales().toLanguageTags()) }
    LaunchedEffect(Unit) { current = AppCompatDelegate.getApplicationLocales().toLanguageTags() }
    val selected = LANGUAGES.firstOrNull { tag -> tag.isNotEmpty() && current.startsWith(tag.substringBefore('-')) } ?: ""
    Chips(LANGUAGES, selected, { stringResource(languageLabel(it)) }) { tag ->
        current = tag
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }
}

@Composable
private fun Section(title: String, hint: String? = null, chips: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(if (chips) 4.dp else 0.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp))
            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            content()
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ChoiceRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val SIGNATURES = listOf(2 to 4, 3 to 4, 4 to 4, 6 to 8)

/** "" = the system language. */
private val LANGUAGES = listOf("", "pt-BR", "en")

private fun languageLabel(tag: String): Int = when (tag) {
    "pt-BR" -> R.string.settings_language_pt_br
    "en" -> R.string.settings_language_en
    else -> R.string.settings_language_system
}

private fun shortSideLabel(side: Int): Int = when (side) {
    720 -> R.string.settings_res_720
    2160 -> R.string.settings_res_2160
    else -> R.string.settings_res_1080
}

private val ProxyMode.label: Int
    get() = when (this) {
        ProxyMode.AUTO -> R.string.settings_proxies_auto
        ProxyMode.ALWAYS -> R.string.settings_proxies_always
        ProxyMode.OFF -> R.string.settings_proxies_off
    }

private val ThemeMode.label: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    }

private val AspectRatio.label: Int
    get() = when (this) {
        AspectRatio.PORTRAIT_9_16 -> R.string.settings_aspect_9_16
        AspectRatio.LANDSCAPE_16_9 -> R.string.settings_aspect_16_9
        AspectRatio.SQUARE_1_1 -> R.string.settings_aspect_1_1
    }

private val CameraLens.label: Int
    get() = when (this) {
        CameraLens.FRONT -> R.string.settings_lens_front
        CameraLens.BACK -> R.string.settings_lens_back
        CameraLens.EXTERNAL -> R.string.settings_lens_external
    }

private val VideoResolution.label: Int
    get() = when (this) {
        VideoResolution.P720 -> R.string.settings_res_720
        VideoResolution.P1080 -> R.string.settings_res_1080
        VideoResolution.P2160 -> R.string.settings_res_2160
    }

private val ClickSound.label: Int
    get() = when (this) {
        ClickSound.BEEP -> R.string.settings_sound_beep
        ClickSound.WOOD -> R.string.settings_sound_wood
        ClickSound.TICK -> R.string.settings_sound_tick
    }

private val VideoCodec.label: Int
    get() = when (this) {
        VideoCodec.H264 -> R.string.settings_codec_h264
        VideoCodec.HEVC -> R.string.settings_codec_hevc
    }
