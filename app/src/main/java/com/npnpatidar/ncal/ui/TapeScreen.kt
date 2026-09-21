package com.npnpatidar.ncal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Notepad-tape calculator screen: editable lined tape on top, result panel,
 * keypad at the bottom — modeled on the CalcTape interaction
 * (type lines, `=` closes a block with separator + subtotal).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TapeScreen(vm: TapeViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val snack = remember { SnackbarHostState() }
    var exportName by remember { mutableStateOf("ncal") }

    LaunchedEffect(state.message) {
        state.message?.let {
            snack.showSnackbar(it)
            vm.clearMessage()
        }
    }

    MaterialTheme(colorScheme = if (state.darkTheme) darkColorScheme() else lightColorScheme()) {
        Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
            Column(
                modifier = Modifier.fillMaxSize().padding(pad).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Toolbar: undo/redo/theme/export.
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = vm::undo) { Text("Undo") }
                    TextButton(onClick = vm::redo) { Text("Redo") }
                    TextButton(onClick = vm::toggleTheme) {
                        Text(if (state.darkTheme) "Light" else "Dark")
                    }
                    TextButton(onClick = { vm.exportCalc(context, exportName) }) { Text("Save .calc") }
                    TextButton(onClick = { vm.exportTxt(context, exportName) }) { Text(".txt") }
                    TextButton(onClick = vm::clear) { Text("AC") }
                }

                // Result panel: current total (tap semantics = copy on device), memory.
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Total  ${state.totalText}", style = MaterialTheme.typography.headlineMedium)
                        Text("Memory ${state.memoryText}   Decimals ${state.decimals}")
                        if (state.errors.isNotEmpty()) {
                            Text(
                                state.errors.first(),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                // The tape itself: free notepad editing; everything recalculates.
                OutlinedTextField(
                    value = state.tapeText,
                    onValueChange = vm::onTapeChange,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    label = { Text("tape — op amount comment per line") },
                )

                // Keypad.
                KeypadGrid(
                    onKey = vm::key,
                    onEquals = vm::equals,
                    onNewLine = vm::newLine,
                    onMemoryAdd = vm::memoryAdd,
                    onMemorySub = vm::memorySub,
                    onMemoryRecall = vm::memoryRecall,
                    onMemoryClear = vm::memoryClear,
                )

                OutlinedTextField(
                    value = exportName,
                    onValueChange = { exportName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("file name for Save (→ Download/ncal/)") },
                    singleLine = true,
                )
            }
        }
    }
}

@Composable
private fun KeypadGrid(
    onKey: (String) -> Unit,
    onEquals: () -> Unit,
    onNewLine: () -> Unit,
    onMemoryAdd: () -> Unit,
    onMemorySub: () -> Unit,
    onMemoryRecall: () -> Unit,
    onMemoryClear: () -> Unit,
) {
    val keys = listOf(
        "7" to { onKey("7") }, "8" to { onKey("8") }, "9" to { onKey("9") },
        "+" to { onKey("\n + ") },
        "4" to { onKey("4") }, "5" to { onKey("5") }, "6" to { onKey("6") },
        "-" to { onKey("\n - ") },
        "1" to { onKey("1") }, "2" to { onKey("2") }, "3" to { onKey("3") },
        "*" to { onKey("\n * ") },
        "0" to { onKey("0") }, "00" to { onKey("00") }, "." to { onKey(".") },
        "/" to { onKey("\n / ") },
        "%" to { onKey("% ") }, "⏎" to onNewLine, "=" to onEquals,
        "M+" to onMemoryAdd,
        "M-" to onMemorySub, "MR" to onMemoryRecall, "MC" to onMemoryClear,
        "^" to { onKey("\n ^ ") },
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
