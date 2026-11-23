package com.vayunmathur.camera.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.camera.R
import com.vayunmathur.camera.data.setDebugOverlay
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow

/** Switch for the viewfinder debug overlay. */
@Composable
internal fun DebugOverlaySetting(viewModel: CameraViewModel) {
    val enabled by viewModel.debug.enabled.collectAsState()
    SettingsSection(title = stringResource(R.string.settings_debug_overlay)) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_debug_overlay_description),
            checked = enabled,
            onCheckedChange = { viewModel.setDebugOverlay(it) },
        )
    }
}
