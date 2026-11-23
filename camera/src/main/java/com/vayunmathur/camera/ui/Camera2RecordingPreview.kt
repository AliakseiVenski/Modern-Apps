package com.vayunmathur.camera.ui

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.vayunmathur.camera.platform.CameraViewModel

/**
 * Viewfinder while a Camera2 recording owns the camera: a TextureView
 * rendering the recording engine's owned preview texture. The texture
 * is published by the ViewModel once the engine has started; until
 * then (the sub-second settle/open window) the preview is black, like
 * every other bind gap in this UI.
 */
@Composable
internal fun Camera2RecordingPreview(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
) {
    val texture by viewModel.camera2RecordingState.previewTexture.collectAsState()
    val liveTexture = texture
    if (liveTexture == null) {
        Box(modifier = modifier.background(Color.Black))
        return
    }
    val bufferSize = viewModel.camera2RecordingState.engine?.ownedPreviewBufferSize
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { view ->
                view.setSurfaceTexture(liveTexture)
                viewModel.camera2RecordingState.engine?.markPreviewTextureBorrowed()
                // A TextureView stretches the buffer to its bounds: scale it back to the buffer's
                // aspect, filling the view (centre crop), or the image looks squeezed.
                view.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                    if (bufferSize != null) fillKeepingAspect(v as TextureView, r - l, b - t, bufferSize)
                }
            }
        },
        modifier = modifier,
    )
}

internal fun fillKeepingAspect(view: TextureView, width: Int, height: Int, buffer: Pair<Int, Int>) {
    if (width <= 0 || height <= 0) return
    val (visibleW, visibleH) = if (height >= width) buffer.second to buffer.first else buffer
    val bufferAspect = visibleW.toFloat() / visibleH
    val viewAspect = width.toFloat() / height
    val scaleX = if (bufferAspect > viewAspect) bufferAspect / viewAspect else 1f
    val scaleY = if (bufferAspect < viewAspect) viewAspect / bufferAspect else 1f
    view.setTransform(android.graphics.Matrix().apply { setScale(scaleX, scaleY, width / 2f, height / 2f) })
}
