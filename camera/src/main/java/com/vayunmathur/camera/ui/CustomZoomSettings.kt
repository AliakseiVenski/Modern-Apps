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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.camera.R
import com.vayunmathur.camera.data.setCustomZoomLevels
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.LabeledTextField
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.Text
import kotlin.math.roundToInt

/**
 * User-configurable zoom-stop pills shown on the ZoomBar.
 *
 * Enter zoom ratios relative to the 1x lens (e.g. 0.5, 1, 3, 5). Empty = use the
 * automatic stops derived from the lens' zoom range. Persisted to DataStore so the
 * pills match this device's real lenses on every launch.
 */
@Composable
internal fun CustomZoomSettings(viewModel: CameraViewModel) {
    val levels by viewModel.customZoomLevels.collectAsState()
    val addFieldState = remember { mutableStateOf("") }

    SettingsSection(title = stringResource(R.string.settings_custom_zoom_levels)) {
        Text(
            stringResource(R.string.settings_custom_zoom_levels_description),
            modifier = Modifier.padding(horizontal = 16.dp),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        )
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            levels.forEachIndexed { index, ratio ->
                val label = formatRatio(ratio)
                SettingsRow(
                    title = label,
                    trailingContent = {
                        IconDelete(
                            modifier = Modifier
                                .size(20.dp)
                                .clickable {
                                    viewModel.setCustomZoomLevels(levels.toMutableList().apply { removeAt(index) })
                                },
                            tint = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        )
                    },
                )
            }
            LabeledTextField(
                value = addFieldState.value,
                onValueChange = { addFieldState.value = it },
                label = stringResource(R.string.settings_custom_zoom_add),
                keyboardType = KeyboardType.Number,
                placeholder = "e.g. 3",
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        // Add button row
        SettingsRow(
            title = stringResource(R.string.settings_custom_zoom_add_button),
            onClick = {
                addFieldState.value.trim().toFloatOrNull()?.takeIf { it > 0f }?.let { ratio ->
                    viewModel.setCustomZoomLevels(levels.toMutableList().apply { add(ratio) })
                    addFieldState.value = ""
                }
            },
            trailingContent = { IconAdd(Modifier.size(20.dp)) },
        )
    }
}

/** Formats a zoom ratio as ".5", "1x", "2x", "1.5x". */
private fun formatRatio(ratio: Float): String =
    if (ratio < 1f) ".${(ratio * 10f).roundToInt()}" else "${ratio.roundToInt()}x"
