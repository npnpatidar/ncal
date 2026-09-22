package com.npnpatidar.ncal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.npnpatidar.ncal.settings.AppSettings
import com.npnpatidar.ncal.settings.NoteSort
import com.npnpatidar.ncal.settings.ThemeMode
import com.npnpatidar.ncal.tape.Grouping

/**
 * Settings page (gear icon in the bottom bar): theme, decimals, indent,
 * thousands grouping, tape font + ruled lines, keypad sizing, haptics/sound,
 * and note sorting. Everything applies live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdate: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Section("Theme") }
            item {
                OptionRow(
                    options = listOf("System" to ThemeMode.SYSTEM, "Light" to ThemeMode.LIGHT, "Dark" to ThemeMode.DARK),
                    selected = settings.themeMode,
                    onSelect = { onUpdate(settings.copy(themeMode = it)) },
                    label = { it.first },
                )
            }
            item { Section("Decimals") }
            item {
                Stepper(
                    value = settings.decimals.toString(),
                    onMinus = { onUpdate(settings.copy(decimals = (settings.decimals - 1).coerceIn(0, 8))) },
                    onPlus = { onUpdate(settings.copy(decimals = (settings.decimals + 1).coerceIn(0, 8))) },
                )
            }
            item { Section("Indentation (spaces before comment)") }
            item {
                Stepper(
                    value = settings.indent.toString(),
                    onMinus = { onUpdate(settings.copy(indent = (settings.indent - 1).coerceIn(1, 8))) },
                    onPlus = { onUpdate(settings.copy(indent = (settings.indent + 1).coerceIn(1, 8))) },
                )
            }
            item { Section("Thousand separator") }
            item {
                OptionRow(
                    options = listOf("Off" to Grouping.OFF, "1,234,567" to Grouping.COMMA, "12,34,567" to Grouping.INDIAN),
                    selected = settings.grouping,
                    onSelect = { onUpdate(settings.copy(grouping = it)) },
                    label = { it.first },
                )
            }
            item { Section("Notepad font size (${settings.tapeFontSp.toInt()}sp)") }
            item {
                Slider(
                    value = settings.tapeFontSp,
                    onValueChange = { onUpdate(settings.copy(tapeFontSp = it)) },
                    valueRange = 12f..24f,
                )
            }
            item { Section("Keypad font size (${settings.keyFontSp.toInt()}sp)") }
            item {
                Slider(
                    value = settings.keyFontSp,
                    onValueChange = { onUpdate(settings.copy(keyFontSp = it)) },
                    valueRange = 12f..28f,
                )
            }
            item { Section("Keyboard height portrait (${settings.keyHeightPortDp.toInt()}dp)") }
            item {
                Slider(
                    value = settings.keyHeightPortDp,
                    onValueChange = { onUpdate(settings.copy(keyHeightPortDp = it)) },
                    valueRange = 40f..80f,
                )
            }
            item { Section("Keyboard height landscape (${settings.keyHeightLandDp.toInt()}dp)") }
            item {
                Slider(
                    value = settings.keyHeightLandDp,
                    onValueChange = { onUpdate(settings.copy(keyHeightLandDp = it)) },
                    valueRange = 36f..64f,
                )
            }
            item {
                SwitchRow("Haptic feedback on keys", settings.haptics) {
                    onUpdate(settings.copy(haptics = it))
                }
            }
            item {
                SwitchRow("Keypress sound", settings.keySound) {
                    onUpdate(settings.copy(keySound = it))
                }
            }
            item { Section("Sort notes") }
            item {
                OptionRow(
                    options = listOf(
                        "Date" to NoteSort.DATE,
                        "Name A–Z" to NoteSort.NAME_ASC,
                        "Name Z–A" to NoteSort.NAME_DESC,
                    ),
                    selected = settings.noteSort,
                    onSelect = { onUpdate(settings.copy(noteSort = it)) },
                    label = { it.first },
                )
            }
            item {
                TextButton(
                    onClick = { onUpdate(AppSettings()) },
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text("Reset to defaults") }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun <T> OptionRow(
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (Pair<String, T>) -> String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { opt ->
            OutlinedButton(
                onClick = { onSelect(opt.second) },
                enabled = opt.second != selected,
            ) { Text(label(opt)) }
        }
    }
}

@Composable
private fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onMinus) { Text("−") }
        Text(value, style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onPlus) { Text("+") }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
