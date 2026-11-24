# Camera features (fork additions)

What the `camera2-session-recording` branch adds on top of upstream `main`, what each feature does, and how it is built. Paths are relative to `camera/src/main/java/com/vayunmathur/camera/`.

## 1. Video recording

### 1.1 Recording on the viewfinder session
Starting a take does not reopen the camera. In the video modes the viewfinder is a lean `Camera2Session` (preview + analysis + a prepared encoder input surface, `TEMPLATE_RECORD`). A take starts the codec and adds the encoder surface to the live repeating request; stopping removes it, signals EOS and finalizes the MP4.

- `platform/SessionRecording.kt`: `RecorderPlan` (tier, fps, mime, HDR), `PreparedRecorder`, `SessionRecorderBinding`; `prepareRecorder` builds a `Camera2RecordingCore` ahead of time (codec configured, input surface created, not started).
- `platform/SessionVideoRecorder.kt`: runs a take on the live session (codec, muxer, mic, adaptive bitrate thread). Start and stop are synchronized and start runs off the main thread.
- `platform/camera2/Camera2SessionVideo.kt`: session extensions `startRecordingTarget`, `stopRecordingTargetBlocking`, `rebindRecorder`.
- A MediaCodec encodes one file, so after each take the session is reconfigured over the open camera with a fresh encoder (`rebindRecorder`).
- The plan is read per setting change and frozen while a take runs (`CameraPreviewSurface`); frame rate and stabilization changes made mid-take apply afterwards.
- A take ends if its session is replaced or closed (`registerCamera2Session`).
- Fallback ladder for the viewfinder session (`ui/Camera2PreviewSession.kt`): 0 analysis + encoder, 1 encoder only, 2 analysis only (the take then uses the old engine that reopens the camera, `Camera2VideoRecordingEngine`), 3 neither. The log prints which path a press took (`Recording` tag).
- Zoom, torch, exposure compensation, tap focus and AE lock keep working during a take because the same session serves them.

### 1.2 Resolution, frame rate, codec
- Settings > Video and the quick panel offer 4K / 1080p / 720p, limited to sizes the lens advertises and the encoder accepts (`platform/VideoOptions.kt`: `tierFits`, `planVideo`).
- Frame rate: automatic prefers 60 fps when lens stream size and encoder allow it, else 30; cinematic stays 30 (`domain/VideoFpsPolicy.kt`). A fixed AE target fps range is set on the request.
- `planVideo` is shared by the viewfinder and the recording, so what you see is what records.

### 1.3 Bitrate
- Automatic bitrate adapts to scene motion, free storage and heat (`domain/AdaptiveVideoBitrate.kt`, `LiveBitrateController`), +50 % at 60 fps.
- Settings > Video > Bitrate: "Automatic bitrate" switch and a Mbps slider. Range is the encoder's range for the codec, capped at 100 Mbps for UHD and 30 Mbps below. A pinned value replaces the adaptive one for the take (`LiveBitrateController(fixed = true)`). Persisted as `camera_video_bitrate_mbps`.

### 1.4 Stabilization
- Switch in Settings and the quick panel (`domain/VideoStabilizationPolicy.kt`): preview stabilization on Android 13+ when listed, else standard video stabilization, plus OIS where listed. OIS is never forced off (a logical multi-camera may not list it though its lenses have it).
- The viewfinder uses the record template and a 16:9 camera buffer (the encoder's shape), because HALs stabilize only when preview and video streams agree. A TextureView resets its buffer size on layout, so `PreviewSurfaceBridge.bufferSize` re-applies it each time and the view centre-crops (`fillKeepingAspect`).
- The `Stabilization` log tag prints the mode the HAL really applied (video, OIS, active physical camera, crop, zoom).

### 1.5 HDR (HLG10)
- Toggle in Settings and the quick panel. Records HEVC Main10 with BT.2020/HLG tags (`Camera2RecordingCore`).
- The HDR viewfinder is a preview-only HLG10 session (`DeviceHandle.createHdrCaptureSession`, `OutputConfiguration.setDynamicRangeProfile`) shown on a `SurfaceView` with the window in HDR colour mode (`Camera2Preview`). Its buffer is pinned to 1920x1080 and stretched to the 9:16 box. The analysis stream cannot share an HLG10 session, so the ISO cap is idle in HDR.

### 1.6 Orientation, audio, mic
- MP4 orientation hint from sensor orientation and display rotation (back: sensor - rotation, front: sensor + rotation).
- Opus audio, preferred physical microphone from Settings. Mic mute works live: the track keeps its timeline and carries silence (`Camera2RecordingCore.audioMuted`).

### 1.7 Lens switching in a take
- Zoom stops are zoom ratios; the HAL swaps physical lens by ratio. During a take, tapping a stop glides the ratio (eased, constant speed in octaves, 0.5-1.2 s) instead of jumping (`platform/CameraControls.kt`, `setZoomRatio(smooth = true)`).

## 2. Shutter lock and ISO limit
"Shutter lock" is a switch in Settings and the quick panel (off by default). On, it holds the shutter at 1/fps and lets ISO absorb brightness, so fps cannot sag in the dark, and ISO limit pills appear (biggest first, "Auto" = as high as the lens goes). Off, the camera's AE alone decides. Saved as `camera_video_shutter_lock`; an old ISO limit turns the switch on.

- The camera's own AE runs by default, so a limited picture is exactly Auto (tap, exposure compensation and AE lock work natively). `platform/PreviewIsoCap.kt` watches the AE result: when it asks for at least 0.9 of a frame of shutter (or more ISO than the limit) it takes over with software AE: AE off, `SENSOR_EXPOSURE_TIME` = one frame, `SENSOR_FRAME_DURATION` pinned (`platform/camera2/Camera2Driver.kt`). When the zoom moves (a lens change is a zoom move) it hands back to the camera's AE as the move starts, because only AE running through the switch carries brightness across lenses; the held exposure resumes 0.7 s after the last zoom move and once AE's exposure has held still for 250 ms. When the held exposure needs under 0.7 of a frame for 400 ms it hands back to AE (1.5 s dwell between switches).
- `domain/ShutterPriorityController.kt`: steers metered luma to a target. Smooth for small errors (about 2 stops/s), faster for large ones (up to 8 stops/s), trusts a scene cut at once, and the first reading corrects in one step.
- `platform/PreviewIsoCap.kt`: feeds it from the analysis Y plane at about 30 Hz; seeded from the camera's own AE exposure so the viewfinder opens at the scene's brightness.
- `domain/HighlightMetering.kt`: metered value is the frame mean, lifted only when more than 2 % of the frame is clipped, so a blown light source is backed off while a merely bright window is left to the mean (as the camera's AE does).
- The controller anchors its target luma to the look the camera's AE had when the cap started (clamped 85-125), so switching Auto to an ISO limit keeps the picture. Updates run at about 30 Hz so ISO changes in small steps. With no cap, the debug overlay shows the frame's luma for comparison.
- `domain/IsoSplit.kt`: ISO above the sensor range is sent as `CONTROL_POST_RAW_SENSITIVITY_BOOST`; the picker reaches 12800 / 25600 where the HAL has a boost range. Overlay and EXIF ISO include the boost.
- Exposure compensation shifts the controller's target by 2^(EV/2.2). Tap meters its own window; long press freezes the controller.
- The same controller runs in the old engine fallback.

## 3. Viewfinder and controls

### 3.1 Layout
- Video modes show the recorded 16:9 frame edge to edge (9:16 in portrait, no rounded corners), under the top and bottom controls, with a fixed black strip below the status bar (`ui/CameraPreviewBox.kt`, `ui/CameraScreen.kt`).
- Camera open: the working output profile is remembered per lens and per photo/video, so rejected richer profiles are not retried every launch (`Camera2PreviewSession`). A "Starting the camera" label shows only while profiles are being probed.
- The camera is released on `ON_STOP` and reopened on `ON_START`, so a screen lock no longer ends in "Camera couldn't start".

### 3.2 Tap and hold
- Tap: AF + AE regions at the point and one AF scan in `AF_MODE_AUTO` (continuous AF locks without scanning when it thinks it is focused), released after 3 s, when the regions clear.
- Long press: locks AE only; focus stays continuous.
- Tap-to-sensor mapping uses the correct rotation direction for a 90 degree sensor (`platform/camera2/MeteringTransform.kt`).

### 3.3 Zoom stops
- Defaults are wide (lens minimum), 1x, 3x.
- Custom stops in Settings: a chip field; type a ratio and press space or Enter to add a pill, tap a pill to remove it. Saved at once (`ui/CustomZoomSettings.kt`).

### 3.4 Panels and settings
- Quick panel (Tune button): resolution, frame rate, stabilization, HDR, ISO limit, debug overlay (`ui/VideoQuickMenu.kt`). Options load when the session registers, so it opens complete.
- Settings are grouped into Video, Photo, Photo and video, System, with thin dividers (`ui/SettingsPage.kt`).
- The brightness slider (exposure compensation) is saved per lens and restored on restart: the key is the active physical camera id from the frame results (or the camera id on a single camera) (`data/CameraSettings.kt`, `onActiveLens`).
- Remember last used mode; Motion Photo switch (off by default).
- Debug overlay (off by default): measured vs target fps, shutter, ISO, focal length, cap state, resolution, codec, bitrate, stabilization.

## 4. Lenses
- Lens list dedupes entries backed by the same physical camera (`domain/LensModel.kt`, `platform/LensSelector.kt`).

## 5. Tests
Unit tests added under `src/test`: fps and stabilization policies, debug stats, fps bitrate scale, lens dedupe, zoom levels, shutter controller, ISO split, highlight metering, fixed bitrate, recording core take config.

## 6. Branches
- `camera2-features`: Camera2 port with the separate recording engine (camera reopens on record).
- `camera2-session-recording`: this document's state (recording on the viewfinder session).
- `develop`: the earlier CameraX implementation.
