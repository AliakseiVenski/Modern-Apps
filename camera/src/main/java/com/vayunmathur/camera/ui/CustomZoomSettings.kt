package com.vayunmathur.camera.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.camera.R
import com.vayunmathur.camera.data.setCustomZoomLevels
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.library.ui.LabeledTextField
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.Text

private fun Float.plain(): String = if (this % 1f == 0f) toInt().toString() else toString()

/**
 * User-configurable zoom-stop pills shown on the zoom bar.
 *
 * One numeric field holds every ratio, separated by spaces (e.g. `0.5 1 3 5`), relative to the 1x
 * lens. Every edit is saved at once; Enter adds a separator. Empty means the automatic stops derived
 * from the lens's zoom range. Stops the lens cannot reach are not shown.
 */
@Composable
internal fun CustomZoomSettings(viewModel: CameraViewModel) {
    val levels by viewModel.customZoomLevels.collectAsState()
    var text by remember { mutableStateOf("") }
    var edited by remember { mutableStateOf(false) }
    // The stored levels hydrate asynchronously; fill the field once, unless the user already typed.
    LaunchedEffect(levels) {
        if (!edited && levels.isNotEmpty()) text = levels.joinToString(" ") { it.plain() } + " "
    }

    fun update(raw: String) {
        edited = true
        text = raw.filter { it.isDigit() || it == '.' || it.isWhitespace() }
        viewModel.setCustomZoomLevels(
            text.split(Regex("\\s+")).mapNotNull { it.toFloatOrNull()?.takeIf { r -> r > 0f } },
        )
    }

    SettingsSection(title = stringResource(R.string.settings_custom_zoom_levels)) {
        Text(
            stringResource(R.string.settings_custom_zoom_levels_description),
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        LabeledTextField(
            value = text,
            onValueChange = { update(it.replace(',', '.')) },
            label = stringResource(R.string.settings_custom_zoom_add),
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(onDone = { if (!text.endsWith(" ")) update("$text ") }),
            placeholder = "0.5 1 3 5",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
