package com.vayunmathur.camera.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.camera.R
import com.vayunmathur.camera.data.setRememberMode
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow

/** Switch that reopens the app in the mode it was last used in. */
@Composable
internal fun RememberModeSetting(viewModel: CameraViewModel) {
    val enabled by viewModel.rememberMode.collectAsState()
    SettingsSection(title = stringResource(R.string.settings_remember_mode)) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_remember_mode_description),
            checked = enabled,
            onCheckedChange = { viewModel.setRememberMode(it) },
        )
    }
}
