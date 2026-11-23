package com.vayunmathur.camera.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.camera.R
import com.vayunmathur.camera.data.setCustomZoomLevels
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.formatZoomLabel
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.LabeledTextField
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.Text

/**
 * User-configurable zoom-stop pills shown on the zoom bar.
 *
 * Enter zoom ratios relative to the 1x lens (e.g. 0.5, 1, 3, 5). Empty means the automatic stops
 * derived from the lens's zoom range. Stops the lens cannot reach are not shown.
 */
@Composable
internal fun CustomZoomSettings(viewModel: CameraViewModel) {
    val levels by viewModel.customZoomLevels.collectAsState()
    var addField by remember { mutableStateOf("") }

    SettingsSection(title = stringResource(R.string.settings_custom_zoom_levels)) {
        Text(
            stringResource(R.string.settings_custom_zoom_levels_description),
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            levels.forEachIndexed { index, ratio ->
                SettingsRow(
                    title = formatZoomLabel(ratio),
                    trailingContent = {
                        IconDelete(
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { viewModel.setCustomZoomLevels(levels.filterIndexed { i, _ -> i != index }) },
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                )
            }
            LabeledTextField(
                value = addField,
                onValueChange = { addField = it },
                label = stringResource(R.string.settings_custom_zoom_add),
                keyboardType = KeyboardType.Decimal,
                placeholder = "3",
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        SettingsRow(
            title = stringResource(R.string.settings_custom_zoom_add_button),
            onClick = {
                addField.trim().toFloatOrNull()?.takeIf { it > 0f }?.let { ratio ->
                    viewModel.setCustomZoomLevels(levels + ratio)
                    addField = ""
                }
            },
            trailingContent = { IconAdd(Modifier.size(20.dp)) },
        )
    }
}
