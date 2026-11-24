package com.vayunmathur.camera.platform.camera2

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView

/**
 * Camera2 migration Phase 0: glue contract between a [TextureView]'s
 * [SurfaceTexture] lifecycle and the session layer.
 *
 * The bridge owns the [Surface] created from the texture and translates the
 * view callbacks into [PreviewSurfaceEvent]s. It deliberately has no session
 * binding yet: consumers map events to [SessionStateMachine] inputs
 * (available/resized → reconfigure, destroyed → teardown) in a later phase.
 *
 * Ownership: the [TextureView] owns the [SurfaceTexture]; this bridge owns the
 * [Surface] it wraps it in and releases that [Surface] on destroy. The texture
 * itself is never released here (returning `true` from the destroyed callback
 * lets the view release it).
 */
sealed interface PreviewSurfaceEvent {
    data class Available(val surface: Surface, val width: Int, val height: Int) : PreviewSurfaceEvent
    data class SizeChanged(val surface: Surface, val width: Int, val height: Int) : PreviewSurfaceEvent

    /** [surface] is already released by the bridge; consumers must drop every reference. */
    data class Destroyed(val surface: Surface) : PreviewSurfaceEvent
}

/** Immutable view of what the bridge currently holds. */
data class PreviewSurfaceState(
    val surface: Surface? = null,
    val width: Int = 0,
    val height: Int = 0,
) {
    val isAvailable: Boolean get() = surface != null
}

class PreviewSurfaceBridge(
    private val onEvent: (PreviewSurfaceEvent) -> Unit,
) : TextureView.SurfaceTextureListener, SurfaceHolder.Callback {

    var state: PreviewSurfaceState = PreviewSurfaceState()
        private set

    /** The current preview surface, or null between destroy and the next available. */
    val surface: Surface? get() = state.surface

    private var texture: SurfaceTexture? = null

    /**
     * Fixed camera buffer size for the TextureView path (null = the view's size). A TextureView resets
     * its buffer to the view size on every layout, so this is re-applied before each event.
     */
    var bufferSize: FrameSize? = null

    /**
     * Sets the buffer size the texture produces. Constrained high-speed
     * sessions reject any output whose size is outside the HFR list, so
     * the slo-mo holder pins the preview texture to the selection size
     * before the session configures; regular sessions leave the view
     * size alone (the HAL scaler handles it).
     */
    fun setDefaultBufferSize(width: Int, height: Int) {
        texture?.setDefaultBufferSize(width, height)
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        this.texture = texture
        bufferSize?.let { texture.setDefaultBufferSize(it.width, it.height) }
        val created = Surface(texture)
        state = PreviewSurfaceState(created, width, height)
        onEvent(PreviewSurfaceEvent.Available(created, width, height))
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        val current = state.surface ?: return
        bufferSize?.let { texture.setDefaultBufferSize(it.width, it.height) }
        state = state.copy(width = width, height = height)
        onEvent(PreviewSurfaceEvent.SizeChanged(current, width, height))
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        this.texture = null
        val current = state.surface
        state = PreviewSurfaceState()
        if (current != null) {
            onEvent(PreviewSurfaceEvent.Destroyed(current))
            current.release()
        }
        // True: the TextureView may release the SurfaceTexture.
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        // Per-frame callback: intentionally not forwarded — consumers poll [state].
    }

    /** Installs this bridge on [view] (or detaches when [view] is re-used elsewhere). */
    fun attachTo(view: TextureView) {
        view.surfaceTextureListener = this
        // A view that already has a texture (re-attach after a config change)
        // never re-fires the available callback; synthesise it.
        val texture = view.surfaceTexture
        if (view.isAvailable && texture != null && state.surface == null) {
            onSurfaceTextureAvailable(texture, view.width, view.height)
        }
    }

    fun detachFrom(view: TextureView) {
        if (view.surfaceTextureListener === this) {
            view.surfaceTextureListener = null
        }
    }

    /** SurfaceView path (HDR viewfinder): the holder owns the [Surface], so it is never released here. */
    fun attachTo(view: SurfaceView) {
        // A SurfaceView sizes its buffer to the view; pin it to the camera buffer (stretched to the 9:16 box).
        bufferSize?.let { view.holder.setFixedSize(it.width, it.height) }
        view.holder.addCallback(this)
        val holderSurface = view.holder.surface
        if (holderSurface.isValid && state.surface == null) {
            state = PreviewSurfaceState(holderSurface, view.width, view.height)
            onEvent(PreviewSurfaceEvent.Available(holderSurface, view.width, view.height))
        }
    }

    fun detachFrom(view: SurfaceView) = view.holder.removeCallback(this)

    override fun surfaceCreated(holder: SurfaceHolder) {
        val created = holder.surface
        val frame = holder.surfaceFrame
        state = PreviewSurfaceState(created, frame.width(), frame.height())
        onEvent(PreviewSurfaceEvent.Available(created, frame.width(), frame.height()))
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val current = state.surface ?: return
        state = state.copy(width = width, height = height)
        onEvent(PreviewSurfaceEvent.SizeChanged(current, width, height))
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val current = state.surface ?: return
        state = PreviewSurfaceState()
        onEvent(PreviewSurfaceEvent.Destroyed(current))
    }
}
