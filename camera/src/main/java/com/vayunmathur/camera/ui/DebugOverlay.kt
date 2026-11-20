package com.vayunmathur.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.DebugStats
import com.vayunmathur.camera.platform.ShutterLock
import com.vayunmathur.camera.platform.VideoBindSummary
import com.vayunmathur.library.ui.Text
import java.util.Locale
import kotlin.math.roundToInt

private const val FPS_TOLERANCE = 2
private val GoodColor = Color(0xFF81C784)
private val WarnColor = Color(0xFFFFB74D)

private data class DebugLine(val text: String, val color: Color = Color.White)

/**
 * Small viewfinder readout of what the camera is really doing: measured fps against the target,
 * shutter, ISO, frame duration, AE mode, the active physical lens and what the encoder was asked for.
 * Fed by capture results of the video preview, so it only has data in the regular video session.
 */
@Composable
internal fun DebugOverlay(viewModel: CameraViewModel, modifier: Modifier = Modifier) {
    val enabled by viewModel.debug.enabled.collectAsState()
    if (!enabled) return
    val stats = viewModel.debug.stats.collectAsState().value
    val bind = viewModel.debug.bind.collectAsState().value
    val lock by viewModel.shutterLock.collectAsState()
    val metering = viewModel.shutterMeter.exposure.collectAsState().value != null
    val luma = viewModel.shutterMeter.luma.collectAsState().value

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        debugLines(stats, bind, lock, metering, luma).forEach {
            Text(it.text, color = it.color, fontSize = 11.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

private fun debugLines(
    stats: DebugStats?,
    bind: VideoBindSummary?,
    lock: ShutterLock,
    metering: Boolean,
    luma: Double?,
): List<DebugLine> = buildList {
    if (stats == null) {
        add(DebugLine("waiting for frames..."))
    } else {
        val target = bind?.fpsTarget ?: 0
        val steady = target == 0 || stats.fps >= target - FPS_TOLERANCE
        val fpsText = String.format(Locale.ROOT, "%.1f fps", stats.fps) + if (target > 0) " / $target" else ""
        add(DebugLine(fpsText, if (steady) GoodColor else WarnColor))
        val shutter = if (stats.exposureNanos > 0) (1e9 / stats.exposureNanos).roundToInt() else 0
        add(DebugLine(String.format(Locale.ROOT, "1/%d  ISO %d  frame %.1f ms", shutter, stats.iso, stats.frameNanos / 1e6)))
        add(DebugLine(aeLine(stats, lock, metering, luma)))
        val focal = String.format(Locale.ROOT, "%.1f", stats.focalMm)
        add(DebugLine("lens ${stats.physicalId ?: "-"}  ${focal}mm  stab ${stats.stabilization}"))
    }
    if (bind != null) {
        val bitrate = if (bind.bitrateMbps > 0) "${bind.bitrateMbps} Mbps" else "auto bitrate"
        add(DebugLine("${bind.resolution ?: "?"}  ${bind.codec}  ${if (bind.hdr) "HLG" else "SDR"}  $bitrate"))
    }
}

private fun aeLine(stats: DebugStats, lock: ShutterLock, metering: Boolean, luma: Double?): String = when {
    stats.aeOn -> "AE on"
    lock.iso == 0 && metering -> "AE off  auto ISO  luma ${luma?.roundToInt() ?: "-"}"
    else -> "AE off  fixed ISO"
}
