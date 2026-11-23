package com.vayunmathur.camera.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/** Group title in Settings, preceded by a thin rule unless it is the first group. */
@Composable
internal fun SettingsCategoryHeader(title: String, first: Boolean = false) {
    Column {
        if (!first) {
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(top = 12.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        Text(
            title,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
