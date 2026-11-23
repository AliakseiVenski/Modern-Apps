package com.vayunmathur.camera.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.camera.R
import com.vayunmathur.library.ui.Text

/** Note over the black viewfinder while the camera starts. */
@Composable
internal fun CameraInitOverlay() {
    Text(
        stringResource(R.string.camera_init_message),
        color = Color.White,
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(32.dp),
    )
}
