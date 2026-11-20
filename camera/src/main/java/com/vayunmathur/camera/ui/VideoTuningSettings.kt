package com.vayunmathur.camera.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.camera.R
import com.vayunmathur.camera.data.setShutterLock
import com.vayunmathur.camera.data.setShutterLockIso
import com.vayunmathur.camera.data.setVideoBitrateMbps
import com.vayunmathur.camera.data.setVideoFps
import com.vayunmathur.camera.data.setVideoHdr
import com.vayunmathur.camera.data.setVideoQuality
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.refreshVideoOptions
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Text
import kotlin.math.roundToInt

private const val DEFAULT_CUSTOM_BITRATE_MBPS = 40
private val DEFAULT_ISO_CHOICES = listOf(100, 200, 400, 800, 1600, 3200, 6400)

/** Resolution, fps, bitrate and HDR pickers, limited to what the selected lens reports. */
@Composable
internal fun VideoTuningSettings(viewModel: CameraViewModel) {
    val tuning by viewModel.videoTuning.collectAsState()
    val options by viewModel.videoOptions.collectAsState()
    val codec by viewModel.videoCodec.collectAsState()
    LaunchedEffect(codec) { viewModel.refreshVideoOptions() }
    val opts = options ?: return
    val auto = stringResource(R.string.settings_video_auto)

    if (opts.qualities.isNotEmpty()) {
        val labels = mapOf(
            "UHD" to stringResource(R.string.video_quality_uhd),
            "FHD" to stringResource(R.string.video_quality_fhd),
            "HD" to stringResource(R.string.video_quality_hd),
            "SD" to stringResource(R.string.video_quality_sd),
        )
        SettingsSection(title = stringResource(R.string.settings_video_resolution)) {
            ChoiceDropdown(
                choices = listOf<Pair<String?, String>>(null to auto) + opts.qualities.map { it to labels.getValue(it) },
                selected = tuning.quality,
                onSelect = { viewModel.setVideoQuality(it) },
            )
        }
    }

    if (opts.fps.isNotEmpty()) {
        SettingsSection(title = stringResource(R.string.settings_video_fps)) {
            ChoiceDropdown(
                choices = listOf(0 to auto) + opts.fps.map { it to stringResource(R.string.settings_video_fps_value, it) },
                selected = tuning.fps,
                onSelect = { viewModel.setVideoFps(it) },
            )
        }
    }

    opts.bitrateRangeMbps(tuning.quality)?.let { range ->
        SettingsSection(title = stringResource(R.string.settings_video_bitrate)) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_video_bitrate_auto),
                checked = tuning.bitrateMbps == 0,
                onCheckedChange = { isAuto ->
                    viewModel.setVideoBitrateMbps(
                        if (isAuto) 0 else DEFAULT_CUSTOM_BITRATE_MBPS.coerceIn(range.first, range.last)
                    )
                },
            )
            if (tuning.bitrateMbps > 0) {
                var value by remember(tuning.bitrateMbps, range) {
                    mutableFloatStateOf(tuning.bitrateMbps.coerceIn(range.first, range.last).toFloat())
                }
                Text(
                    stringResource(R.string.settings_video_bitrate_value, value.roundToInt()),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    onValueChangeFinished = { viewModel.setVideoBitrateMbps(value.roundToInt()) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }

    if (opts.hdrSupported) {
        SettingsSection(title = stringResource(R.string.settings_video_hdr)) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_video_hdr_description),
                checked = tuning.hdr,
                onCheckedChange = { viewModel.setVideoHdr(it) },
            )
        }
    }

    ShutterLockSettings(viewModel)
}

/** Experimental fixed 1/fps shutter with a fixed ISO, to keep the frame rate from dropping in the dark. */
@Composable
private fun ShutterLockSettings(viewModel: CameraViewModel) {
    val lock by viewModel.shutterLock.collectAsState()
    val isoStops by viewModel.isoStops.collectAsState()
    SettingsSection(title = stringResource(R.string.settings_shutter_lock)) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_shutter_lock_description),
            checked = lock.enabled,
            onCheckedChange = { viewModel.setShutterLock(it) },
        )
        if (lock.enabled) {
            ChoiceDropdown(
                choices = isoStops.ifEmpty { DEFAULT_ISO_CHOICES }
                    .map { it to stringResource(R.string.settings_iso_value, it) },
                selected = lock.iso,
                onSelect = { viewModel.setShutterLockIso(it) },
            )
        }
    }
}

/** Outlined-button dropdown over (value, label) pairs. */
@Composable
private fun <T> ChoiceDropdown(choices: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                (choices.firstOrNull { it.first == selected } ?: choices.first()).second,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
                maxLines = 1,
            )
            IconArrowDropDown()
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (value, label) ->
                SelectableDropdownMenuItem(
                    selected = value == selected,
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                    text = { Text(label) },
                    selectedLeadingIcon = { IconCheck() },
                )
            }
        }
    }
}
