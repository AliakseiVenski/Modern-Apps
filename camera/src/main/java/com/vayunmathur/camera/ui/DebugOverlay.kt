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
import com.vayunmathur.camera.domain.DebugStats
import com.vayunmathur.camera.platform.CameraViewModel
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
 * shutter, ISO and focal length from the capture results, and what the current recording asked
 * for (resolution, codec, bitrate, stabilization). Works on the preview and while recording.
 */
@Composable
internal fun DebugOverlay(viewModel: CameraViewModel, modifier: Modifier = Modifier) {
    val enabled by viewModel.debug.enabled.collectAsState()
    if (!enabled) return
    val stats = viewModel.debug.stats.collectAsState().value
    val bind = viewModel.debug.bind.collectAsState().value
    val tuning by viewModel.videoTuning.collectAsState()
    val capStatus = viewModel.previewIsoCap.status.collectAsState().value

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        val settings = buildList {
            capStatus?.let { add(DebugLine(it)) }
            if (tuning.hdr) add(DebugLine("HDR on: HLG viewfinder, no ISO cap preview"))
        }
        (debugLines(stats, bind) + settings).forEach {
            Text(it.text, color = it.color, fontSize = 11.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

private fun debugLines(stats: DebugStats?, bind: VideoBindSummary?): List<DebugLine> = buildList {
    if (stats == null) {
        add(DebugLine("waiting for frames..."))
    } else {
        val target = bind?.fpsTarget ?: 0
        val steady = target == 0 || stats.fps >= target - FPS_TOLERANCE
        val fpsText = String.format(Locale.ROOT, "%.1f fps", stats.fps) + if (target > 0) " / $target" else ""
        add(DebugLine(fpsText, if (steady) GoodColor else WarnColor))
        val shutter = if (stats.exposureNanos > 0) (1e9 / stats.exposureNanos).roundToInt() else 0
        add(DebugLine(String.format(Locale.ROOT, "1/%d  ISO %d  %.1fmm", shutter, stats.iso, stats.focalMm)))
    }
    if (bind != null) {
        add(DebugLine("${bind.resolution}  ${bind.codec}  ${bind.bitrateMbps} Mbps"))
        add(DebugLine("stab ${bind.stabilization}"))
    }
}
