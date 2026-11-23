package com.vayunmathur.camera.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.camera.R
import com.vayunmathur.camera.data.setVideoFps
import com.vayunmathur.camera.data.setVideoStabilizationEnabled
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.refreshVideoOptions
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Text

/**
 * Video frame rate and stabilization, limited to what the lens a recording would use reports.
 * Both are read when a recording starts, so changing them never rebinds anything.
 */
@Composable
internal fun VideoTuningSettings(viewModel: CameraViewModel) {
    val tuning by viewModel.videoTuning.collectAsState()
    val options by viewModel.videoOptions.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshVideoOptions() }
    val opts = options ?: return
    val auto = stringResource(R.string.settings_video_auto)

    if (opts.fps.isNotEmpty()) {
        SettingsSection(title = stringResource(R.string.settings_video_fps)) {
            ChoiceDropdown(
                choices = listOf(0 to auto) + opts.fps.map { it to stringResource(R.string.settings_video_fps_value, it) },
                selected = tuning.fps,
                onSelect = { viewModel.setVideoFps(it) },
            )
        }
    }

    if (opts.stabilizationSupported) {
        SettingsSection(title = stringResource(R.string.settings_video_stabilization)) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_video_stabilization_description),
                checked = tuning.stabilizationEnabled,
                onCheckedChange = { viewModel.setVideoStabilizationEnabled(it) },
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
