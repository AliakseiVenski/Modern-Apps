package com.vayunmathur.camera.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.camera.R
import com.vayunmathur.camera.data.setDebugOverlay
import com.vayunmathur.camera.data.setShutterLock
import com.vayunmathur.camera.data.setShutterLockIso
import com.vayunmathur.camera.data.setVideoFps
import com.vayunmathur.camera.data.setVideoHdr
import com.vayunmathur.camera.data.setVideoQuality
import com.vayunmathur.camera.data.setVideoStabilizationEnabled
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.FALLBACK_LOCK_ISO
import com.vayunmathur.camera.platform.refreshVideoOptions
import com.vayunmathur.library.ui.IconIso
import com.vayunmathur.library.ui.IconTimer
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import kotlin.math.roundToInt

private val PanelColor = Color(0xF2141518)
private val SectionColor = Color.White.copy(alpha = 0.55f)
private val CardColor = Color.White.copy(alpha = 0.07f)
private val AccentColor = Color(0xFF8AB4F8)

/**
 * Floating video panel over the viewfinder: resolution, frame rate, shutter lock (with ISO and a
 * live exposure readout) and HDR, limited to what the selected lens reports. Tapping outside
 * the panel calls [onDismiss].
 */
@Composable
internal fun VideoQuickMenu(viewModel: CameraViewModel, onDismiss: () -> Unit) {
    val tuning by viewModel.videoTuning.collectAsState()
    val options by viewModel.videoOptions.collectAsState()
    val lock by viewModel.shutterLock.collectAsState()
    val codec by viewModel.videoCodec.collectAsState()
    val isoStops by viewModel.isoStops.collectAsState()
    val debugOn by viewModel.debug.enabled.collectAsState()
    val metered = viewModel.shutterMeter.exposure.collectAsState().value
    LaunchedEffect(codec) { viewModel.refreshVideoOptions() }
    val opts = options
    val auto = stringResource(R.string.settings_video_auto)

    val fps = viewModel.videoFpsTarget
    val readout = when {
        !lock.enabled || fps <= 0 -> null
        lock.iso > 0 -> stringResource(R.string.settings_shutter_readout, fps, lock.iso)
        metered != null -> stringResource(
            R.string.settings_shutter_readout, (1e9 / metered.timeNanos).roundToInt(), metered.iso
        )
        else -> stringResource(R.string.settings_shutter_meter_unavailable, FALLBACK_LOCK_ISO)
    }

    Box(modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } }) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures { } },
            shape = RoundedCornerShape(28.dp),
            color = PanelColor,
            contentColor = Color.White,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.quick_menu_title),
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (readout != null) {
                        Text(readout, color = AccentColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }

                if (opts != null && opts.qualities.isNotEmpty()) {
                    val labels = videoQualityLabels()
                    PillRow(
                        title = stringResource(R.string.settings_video_resolution),
                        choices = listOf<Pair<String?, String>>(null to auto) +
                            opts.qualities.map { it to labels.getValue(it) },
                        selected = tuning.quality,
                        onSelect = { viewModel.setVideoQuality(it) },
                    )
                }
                if (opts != null && opts.fps.isNotEmpty()) {
                    PillRow(
                        title = stringResource(R.string.settings_video_fps),
                        choices = listOf(0 to auto) +
                            opts.fps.map { it to stringResource(R.string.settings_video_fps_value, it) },
                        selected = tuning.fps,
                        onSelect = { viewModel.setVideoFps(it) },
                    )
                }

                ToggleCard(
                    title = stringResource(R.string.settings_shutter_lock),
                    subtitle = stringResource(R.string.settings_shutter_lock_short),
                    checked = lock.enabled,
                    onCheckedChange = { viewModel.setShutterLock(it) },
                    leading = { IconTimer(Modifier.size(22.dp), Color.White) },
                ) {
                    if (lock.enabled) {
                        PillRow(
                            title = stringResource(R.string.settings_shutter_iso),
                            choices = listOf(0 to auto) +
                                isoStops.ifEmpty { DEFAULT_ISO_CHOICES }.map { it to it.toString() },
                            selected = lock.iso,
                            onSelect = { viewModel.setShutterLockIso(it) },
                            leading = { IconIso(Modifier.size(16.dp), SectionColor) },
                        )
                    }
                }

                if (opts?.hdrSupported == true) {
                    ToggleCard(
                        title = stringResource(R.string.settings_video_hdr),
                        subtitle = stringResource(R.string.settings_video_hdr_short),
                        checked = tuning.hdr,
                        onCheckedChange = { viewModel.setVideoHdr(it) },
                    )
                }

                ToggleCard(
                    title = stringResource(R.string.settings_video_stabilization),
                    subtitle = stringResource(R.string.settings_video_stabilization_short),
                    checked = tuning.stabilizationEnabled,
                    onCheckedChange = { viewModel.setVideoStabilizationEnabled(it) },
                )

                ToggleCard(
                    title = stringResource(R.string.settings_debug_overlay),
                    subtitle = stringResource(R.string.settings_debug_overlay_short),
                    checked = debugOn,
                    onCheckedChange = { viewModel.setDebugOverlay(it) },
                )
            }
        }
    }
}

@Composable
private fun <T> PillRow(
    title: String,
    choices: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            leading?.invoke()
            Text(title.uppercase(), color = SectionColor, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            choices.forEach { (value, label) ->
                val isSelected = value == selected
                Surface(
                    onClick = { onSelect(value) },
                    shape = RoundedCornerShape(50),
                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.10f),
                    contentColor = if (isSelected) Color.Black else Color.White,
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    leading: (@Composable () -> Unit)? = null,
    extra: @Composable () -> Unit = {},
) {
    Surface(shape = RoundedCornerShape(20.dp), color = CardColor, contentColor = Color.White) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                leading?.invoke()
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(subtitle, color = SectionColor, fontSize = 12.sp)
                }
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            }
            extra()
        }
    }
}
