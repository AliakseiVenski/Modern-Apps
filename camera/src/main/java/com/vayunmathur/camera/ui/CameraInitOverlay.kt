package com.vayunmathur.camera.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.camera.R
import com.vayunmathur.library.ui.Text
import kotlinx.coroutines.delay

private const val SHOW_AFTER_MS = 300L

/** Note over the black viewfinder while the camera starts; hidden for a quick open. */
@Composable
internal fun CameraInitOverlay() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SHOW_AFTER_MS)
        visible = true
    }
    if (!visible) return
    Text(
        stringResource(R.string.camera_init_message),
        color = Color.White,
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(32.dp),
    )
}
