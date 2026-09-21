package com.npnpatidar.ncal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalTextInputService
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

/**
 * Notepad calculator:
 * - Top bar: hamburger (opens notes sidebar) + current file name (tap to rename).
 * - Editable notepad area below it.
 * - Middle strip: [calculator keypad] [normal keyboard] [running total].
 * - 4x5 calculator keypad.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TapeScreen(vm: TapeViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val snack = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var renameOpen by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    LaunchedEffect(state.message) {
        state.message?.let {
            snack.showSnackbar(it)
            vm.clearMessage()
        }
    }

    MaterialTheme(colorScheme = if (state.darkTheme) darkColorScheme() else lightColorScheme()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    Text(
                        "Notes",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(16.dp),
                    )
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(state.notes, key = { it.id }) { note ->
                            NavigationDrawerItem(
                                label = { Text(note.name) },
                                selected = note.id == state.noteId,
                                onClick = {
                                    vm.selectNote(note.id)
                                    scope.launch { drawerState.close() }
                                },
                                badge = {
                                    IconButton(onClick = { vm.deleteNote(note.id) }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Delete note")
                                    }
                                },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                    }
                    TextButton(onClick = { vm.createNote() }) { Text("+ New note") }
                    HorizontalDivider()
                    TextButton(onClick = { vm.exportCalc() }) { Text("Save .calc → Download/ncal") }
                    TextButton(onClick = { vm.exportTxt() }) { Text("Save .txt → Download/ncal") }
                    TextButton(onClick = { vm.toggleTheme() }) {
                        Text(if (state.darkTheme) "Light theme" else "Dark theme")
                    }
                }
            },
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                state.noteName.ifBlank { "ncal" },
                                modifier = Modifier.clickable {
                                    renameText = state.noteName
                                    renameOpen = true
                                },
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "Open notes")
                            }
                        },
                    )
                },
                snackbarHost = { SnackbarHost(snack) },
            ) { pad ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Notepad (editable area). Outside SYSTEM mode the field is
                    // read-only AND detached from the input service, so tapping
                    // it only moves the cursor — only ABC ever raises the
                    // system keyboard. All input then comes from the keypad.
                    val systemMode = state.keypadMode == KeypadMode.SYSTEM
                    val tapeField: @Composable () -> Unit = {
                        OutlinedTextField(
                            value = state.tapeText,
                            onValueChange = vm::onTapeChange,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            readOnly = !systemMode,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            label = { Text("notepad — op amount comment per line") },
                        )
                    }
                    if (systemMode) {
                        tapeField()
                    } else {
                        CompositionLocalProvider(LocalTextInputService provides null) {
                            tapeField()
                        }
                    }

                    if (state.errors.isNotEmpty()) {
                        Text(
                            state.errors.first(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    // Middle strip: exactly one of calculator keypad / normal
                    // keyboard / hidden is active, then the running total.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { vm.setKeypadMode(KeypadMode.CALC) },
                            enabled = state.keypadMode != KeypadMode.CALC,
                        ) { Text("Calc") }
                        OutlinedButton(
                            onClick = { vm.setKeypadMode(KeypadMode.SYSTEM) },
                            enabled = state.keypadMode != KeypadMode.SYSTEM,
                        ) { Text("ABC") }
                        OutlinedButton(
                            onClick = { vm.setKeypadMode(KeypadMode.HIDDEN) },
                            enabled = state.keypadMode != KeypadMode.HIDDEN,
                        ) { Text("Hide") }
                        Text(
                            state.totalText,
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    if (state.keypadMode == KeypadMode.CALC) {
                        KeypadGrid(
                            onDigit = vm::key,
                            onOp = vm::key,
                            onEquals = vm::equals,
                            onClear = vm::clear,
                            onUndo = vm::undo,
                            onBackspace = vm::backspace,
                        )
                    }
                }
            }
        }

        if (renameOpen) {
            AlertDialog(
                onDismissRequest = { renameOpen = false },
                title = { Text("Rename note") },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            vm.renameNote(renameText)
                            renameOpen = false
                        },
                    ) { Text("OK") }
                },
                dismissButton = {
                    TextButton(onClick = { renameOpen = false }) { Text("Cancel") }
                },
            )
        }
    }
}

/**
 * 4 columns x 5 rows = 20 buttons:
 * AC (clears whole notepad), undo, backspace, %, =,
 * digits 0-9 + ".", and + - x (multiply) ÷ (divide).
 * Last column top to bottom: divide, multiply, subtract, add, equals.
 */
@Composable
private fun KeypadGrid(
    onDigit: (String) -> Unit,
    onOp: (String) -> Unit,
    onEquals: () -> Unit,
    onClear: () -> Unit,
    onUndo: () -> Unit,
    onBackspace: () -> Unit,
) {
    // Pair(display label, action). Display uses x ÷ - glyphs; inserted text stays ASCII.
    val keys: List<Pair<String, () -> Unit>> = listOf(
        "AC" to onClear,
        "undo" to onUndo,
        "⌫" to onBackspace,
        "÷" to { onOp("\n / ") },
        "7" to { onDigit("7") },
        "8" to { onDigit("8") },
        "9" to { onDigit("9") },
        "×" to { onOp("\n * ") },
        "4" to { onDigit("4") },
        "5" to { onDigit("5") },
        "6" to { onDigit("6") },
        "−" to { onOp("\n - ") },
        "1" to { onDigit("1") },
        "2" to { onDigit("2") },
        "3" to { onDigit("3") },
        "+" to { onOp("\n + ") },
        "0" to { onDigit("0") },
        "." to { onDigit(".") },
        "%" to { onOp("% ") },
        "=" to onEquals,
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(keys) { (label, action) ->
            Button(onClick = action) { Text(label) }
        }
    }
}
