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
import com.vayunmathur.camera.data.setVideoFps
import com.vayunmathur.camera.data.setVideoStabilizationEnabled
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.refreshVideoOptions
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text

private val PanelColor = Color(0xF2141518)
private val SectionColor = Color.White.copy(alpha = 0.55f)
private val CardColor = Color.White.copy(alpha = 0.07f)

/**
 * Floating video panel over the viewfinder: frame rate, stabilization and the debug overlay,
 * limited to what the lens reports. Tapping outside the panel calls [onDismiss].
 */
@Composable
internal fun VideoQuickMenu(viewModel: CameraViewModel, onDismiss: () -> Unit) {
    val tuning by viewModel.videoTuning.collectAsState()
    val options by viewModel.videoOptions.collectAsState()
    val debugOn by viewModel.debug.enabled.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshVideoOptions() }
    val opts = options
    val auto = stringResource(R.string.settings_video_auto)

    Box(modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } }) {
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
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
                Text(
                    stringResource(R.string.quick_menu_title),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                if (opts != null && opts.fps.isNotEmpty()) {
                    PillRow(
                        title = stringResource(R.string.settings_video_fps),
                        choices = listOf(0 to auto) +
                            opts.fps.map { it to stringResource(R.string.settings_video_fps_value, it) },
                        selected = tuning.fps,
                        onSelect = { viewModel.setVideoFps(it) },
                    )
                }

                if (opts?.stabilizationSupported == true) {
                    ToggleCard(
                        title = stringResource(R.string.settings_video_stabilization),
                        subtitle = stringResource(R.string.settings_video_stabilization_short),
                        checked = tuning.stabilizationEnabled,
                        onCheckedChange = { viewModel.setVideoStabilizationEnabled(it) },
                        leading = { IconSettings(Modifier.size(22.dp), Color.White) },
                    )
                }

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
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), color = SectionColor, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
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
) {
    Surface(shape = RoundedCornerShape(20.dp), color = CardColor, contentColor = Color.White) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
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
    }
}
