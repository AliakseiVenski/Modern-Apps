package com.vayunmathur.camera.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.camera.R
import com.vayunmathur.camera.data.setShutterLock
import com.vayunmathur.camera.data.setVideoFps
import com.vayunmathur.camera.data.setVideoHdr
import com.vayunmathur.camera.data.setVideoQuality
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.refreshVideoOptions
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text

/**
 * Quick video menu opened from the viewfinder: resolution, frame rate, shutter lock and HDR,
 * limited to what the selected lens reports. Content for a dropdown anchored on the top-bar button.
 */
@Composable
internal fun VideoQuickMenu(viewModel: CameraViewModel) {
    val tuning by viewModel.videoTuning.collectAsState()
    val options by viewModel.videoOptions.collectAsState()
    val lock by viewModel.shutterLock.collectAsState()
    val codec by viewModel.videoCodec.collectAsState()
    LaunchedEffect(codec) { viewModel.refreshVideoOptions() }
    val opts = options
    val auto = stringResource(R.string.settings_video_auto)

    Column(
        modifier = Modifier.widthIn(min = 260.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (opts != null && opts.qualities.isNotEmpty()) {
            val labels = videoQualityLabels()
            ChipRow(
                title = stringResource(R.string.settings_video_resolution),
                choices = listOf<Pair<String?, String>>(null to auto) + opts.qualities.map { it to labels.getValue(it) },
                selected = tuning.quality,
                onSelect = { viewModel.setVideoQuality(it) },
            )
        }
        if (opts != null && opts.fps.isNotEmpty()) {
            ChipRow(
                title = stringResource(R.string.settings_video_fps),
                choices = listOf(0 to auto) + opts.fps.map { it to stringResource(R.string.settings_video_fps_value, it) },
                selected = tuning.fps,
                onSelect = { viewModel.setVideoFps(it) },
            )
        }
        SwitchRow(stringResource(R.string.settings_shutter_lock), lock.enabled) { viewModel.setShutterLock(it) }
        if (opts?.hdrSupported == true) {
            SwitchRow(stringResource(R.string.settings_video_hdr), tuning.hdr) { viewModel.setVideoHdr(it) }
        }
    }
}

@Composable
private fun <T> ChipRow(title: String, choices: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            choices.forEach { (value, label) ->
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
