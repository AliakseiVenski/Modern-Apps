package com.vayunmathur.camera.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.camera.R
import com.vayunmathur.camera.data.setCustomZoomLevels
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.formatZoomLabel
import com.vayunmathur.library.ui.IconClear
import com.vayunmathur.library.ui.LocalContentColor
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.Text

private val ZOOM_FIELD_HEIGHT = 40.dp

/**
 * User-configurable zoom-stop pills shown on the zoom bar.
 *
 * Each ratio (relative to the 1x lens) is a chip with an x to remove it; the numeric field after the
 * chips adds the typed ratio on space or Enter and saves at once. Empty means the automatic stops
 * derived from the lens's zoom range. Stops the lens cannot reach are not shown.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CustomZoomSettings(viewModel: CameraViewModel) {
    val levels by viewModel.customZoomLevels.collectAsState()
    var input by remember { mutableStateOf("") }

    fun add(text: String) {
        val ratio = text.toFloatOrNull()?.takeIf { it > 0f } ?: return
        viewModel.setCustomZoomLevels(levels + ratio)
    }

    SettingsSection(title = stringResource(R.string.settings_custom_zoom_levels)) {
        Text(
            stringResource(R.string.settings_custom_zoom_levels_description),
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            levels.forEach { ratio ->
                androidx.compose.material3.InputChip(
                    selected = false,
                    onClick = { viewModel.setCustomZoomLevels(levels - ratio) },
                    label = { Text(formatZoomLabel(ratio), maxLines = 1) },
                    modifier = Modifier.height(ZOOM_FIELD_HEIGHT),
                    shape = RoundedCornerShape(50),
                    trailingIcon = { IconClear(Modifier.size(18.dp), contentDescription = null) },
                )
            }
            val style = MaterialTheme.typography.bodyMedium.copy(color = LocalContentColor.current)
            BasicTextField(
                value = input,
                onValueChange = { typed ->
                    val clean = typed.replace(',', '.')
                    if (clean.any { it.isWhitespace() }) {
                        clean.split(Regex("\\s+")).forEach { add(it) }
                        input = ""
                    } else {
                        input = clean.filter { it.isDigit() || it == '.' }
                    }
                },
                modifier = Modifier.widthIn(min = 140.dp, max = 240.dp),
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(LocalContentColor.current),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    add(input)
                    input = ""
                }),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .height(ZOOM_FIELD_HEIGHT)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (input.isEmpty()) {
                            Text(
                                stringResource(R.string.settings_custom_zoom_add),
                                style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            )
                        }
                        inner()
                    }
                },
            )
        }
    }
}
