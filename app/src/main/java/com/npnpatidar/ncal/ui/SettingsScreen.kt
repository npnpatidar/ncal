package com.npnpatidar.ncal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.npnpatidar.ncal.settings.AppSettings
import com.npnpatidar.ncal.settings.NoteSort
import com.npnpatidar.ncal.settings.ThemeMode
import com.npnpatidar.ncal.tape.Grouping
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onUpdate: (AppSettings) -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    BackHandler(onBack = onBack)
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            item { Section("Notepad font size (${settings.tapeFontSp.roundToInt()}sp)") }
            item {
                PersistedSlider(
                    value = settings.tapeFontSp,
                    range = 6f..32f,
                    label = "Notepad font size",
                    onCommit = { onUpdate(settings.copy(tapeFontSp = it)) },
                )
            }
            item { Section("Keypad font size (${settings.keyFontSp.roundToInt()}sp)") }
            item {
                PersistedSlider(
                    value = settings.keyFontSp,
                    range = 12f..28f,
                    label = "Keypad font size",
                    onCommit = { onUpdate(settings.copy(keyFontSp = it)) },
                )
            }
            item { Section("Keyboard height portrait (${settings.keyHeightPortDp.roundToInt()}dp)") }
            item {
                PersistedSlider(
                    value = settings.keyHeightPortDp,
                    range = 48f..80f,
                    label = "Keyboard height portrait",
                    onCommit = { onUpdate(settings.copy(keyHeightPortDp = it)) },
                )
            }
            item { Section("Keyboard height landscape (${settings.keyHeightLandDp.roundToInt()}dp)") }
            item {
                PersistedSlider(
                    value = settings.keyHeightLandDp,
                    range = 48f..64f,
                    label = "Keyboard height landscape",
                    onCommit = { onUpdate(settings.copy(keyHeightLandDp = it)) },
                )
            }
            item { SwitchRow("Haptic feedback on keys", settings.haptics) { onUpdate(settings.copy(haptics = it)) } }
            item { SwitchRow("Keypress sound", settings.keySound) { onUpdate(settings.copy(keySound = it)) } }
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
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            OutlinedButton(
                onClick = { if (option.second != selected) onSelect(option.second) },
                enabled = true,
                modifier = Modifier.semantics { this.selected = option.second == selected },
            ) { Text(label(option)) }
        }
    }
}

@Composable
private fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onMinus, modifier = Modifier.semantics { contentDescription = "Decrease" }) { Text("−") }
        Text(value, style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onPlus, modifier = Modifier.semantics { contentDescription = "Increase" }) { Text("+") }
    }
}

@Composable
private fun PersistedSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: String,
    onCommit: (Float) -> Unit,
) {
    var draft by rememberSaveable(value) { mutableFloatStateOf(value) }
    Slider(
        value = draft,
        onValueChange = { draft = it },
        onValueChangeFinished = { onCommit(draft) },
        valueRange = range,
        modifier = Modifier.semantics {
            contentDescription = label
            stateDescription = draft.roundToInt().toString()
        },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onChange,
            )
            .semantics { role = Role.Switch },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = null)
    }
}
