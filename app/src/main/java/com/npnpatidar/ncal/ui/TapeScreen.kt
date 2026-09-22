package com.npnpatidar.ncal.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextInputService
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.npnpatidar.ncal.settings.ThemeMode
import com.npnpatidar.ncal.storage.NotesRepository
import kotlinx.coroutines.launch

/**
 * Notepad calculator:
 * - Top bar: hamburger (opens notes sidebar) + current file name (tap to rename).
 * - Editable notepad area below it.
 * - Middle strip: [calculator keypad] [normal keyboard] [running total].
 * - 4x5 calculator keypad.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TapeScreen(vm: TapeViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val snack = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var renameOpen by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<NotesRepository.NoteMeta?>(null) }
    var pendingDelete by remember { mutableStateOf<NotesRepository.NoteMeta?>(null) }
    var noteMenu by remember { mutableStateOf<NotesRepository.NoteMeta?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isNotEmpty()) vm.importFiles(uris)
    }
    var showSettings by remember { mutableStateOf(false) }
    val tapeFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // ABC mode: cursor goes straight into the note and the keyboard opens.
    LaunchedEffect(state.keypadMode, showSettings) {
        if (state.keypadMode == KeypadMode.SYSTEM && !showSettings) {
            tapeFocus.requestFocus()
            keyboard?.show()
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snack.showSnackbar(it)
            vm.clearMessage()
        }
    }

    val dark = when (state.settings.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Notes",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { vm.createNote() }) {
                            Icon(Icons.Filled.Add, contentDescription = "New note")
                        }
                    }
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(state.notes, key = { it.id }) { note ->
                            // Long-press a note for rename / duplicate /
                            // export / delete; tap to open it.
                            Box(
                                modifier = Modifier.padding(horizontal = 8.dp)
                                    .combinedClickable(
                                        onClick = {
                                            vm.selectNote(note.id)
                                            scope.launch { drawerState.close() }
                                        },
                                        onLongClick = { noteMenu = note },
                                    ),
                            ) {
                                NavigationDrawerItem(
                                    label = { Text(note.name) },
                                    selected = note.id == state.noteId,
                                    onClick = {},
                                )
                            }
                        }
                    }
                    TextButton(onClick = { importLauncher.launch("*/*") }) {
                        Text("Import .calc / .txt")
                    }
                    HorizontalDivider()
                    TextButton(onClick = { vm.exportCalc() }) { Text("Save .calc → Download/ncal") }
                    TextButton(onClick = { vm.exportTxt() }) { Text("Save .txt → Download/ncal") }
                    Text(
                        "ncal ${state.appVersion}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            },
        ) {
            if (showSettings) {
                SettingsScreen(
                    settings = state.settings,
                    onUpdate = vm::updateSettings,
                    onBack = { showSettings = false },
                )
            } else {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                state.noteName.ifBlank { "ncal" },
                                modifier = Modifier.clickable {
                                    renameTarget = state.notes.firstOrNull { it.id == state.noteId }
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
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = false,
                            onClick = { scope.launch { drawerState.open() } },
                            icon = { Icon(Icons.Filled.Menu, contentDescription = "Notes") },
                            label = { Text("Notes") },
                        )
                        NavigationBarItem(
                            selected = false,
                            onClick = { showSettings = true },
                            icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                            label = { Text("Settings") },
                        )
                    }
                },
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
                    val st = state.settings
                    val tapeFontSize = st.tapeFontSp.sp
                    val tapeLineHeight = (st.tapeFontSp * 1.6f).sp
                    Text(
                        "notepad — op amount comment per line",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val density = LocalDensity.current
                    val ruleColor = Color(st.lineColorArgb)
                    // Rules sit exactly on the gaps between text lines: with an
                    // explicit lineHeight every line box is lineHeight tall, so
                    // the boundaries are exact regardless of font metrics.
                    val ruleTopPx = with(density) { 16.dp.toPx() }
                    val ruleGapPx = with(density) { tapeLineHeight.toPx() }
                    val ruleStrokePx = with(density) { 1.dp.toPx() }
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        if (st.showLines) {
                            Canvas(modifier = Modifier.matchParentSize()) {
                                var y = ruleTopPx + ruleGapPx
                                while (y < size.height - 4.dp.toPx()) {
                                    drawLine(ruleColor, Offset(0f, y), Offset(size.width, y), ruleStrokePx)
                                    y += ruleGapPx
                                }
                            }
                        }
                        val tapeField: @Composable () -> Unit = {
                            OutlinedTextField(
                                value = state.tapeText,
                                onValueChange = vm::onTapeChange,
                                modifier = Modifier.fillMaxWidth().fillMaxHeight().focusRequester(tapeFocus),
                                readOnly = !systemMode,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = tapeFontSize,
                                    lineHeight = tapeLineHeight,
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                ),
                            )
                        }
                        if (systemMode) {
                            tapeField()
                        } else {
                            CompositionLocalProvider(LocalTextInputService provides null) {
                                tapeField()
                            }
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

                    val landscape = LocalConfiguration.current.orientation ==
                        android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    val keyFont: TextUnit = st.keyFontSp.sp
                    val keyHeight: Dp =
                        (if (landscape) st.keyHeightLandDp else st.keyHeightPortDp).dp
                    if (state.keypadMode == KeypadMode.CALC) {
                        KeypadGrid(
                            onDigit = vm::key,
                            onOp = vm::key,
                            onEquals = vm::equals,
                            onClear = vm::clear,
                            onUndo = vm::undo,
                            onBackspace = vm::backspace,
                            keyFontSp = keyFont,
                            keyHeight = keyHeight,
                            hapticsOn = state.settings.haptics,
                            soundOn = state.settings.keySound,
                        )
                    }
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
                            renameTarget?.let { vm.renameNoteById(it.id, renameText) }
                            renameOpen = false
                        },
                    ) { Text("OK") }
                },
                dismissButton = {
                    TextButton(onClick = { renameOpen = false }) { Text("Cancel") }
                },
            )
        }

        // Long-press menu for a note: rename / duplicate / export / delete.
        val menuNote = noteMenu
        if (menuNote != null) {
            AlertDialog(
                onDismissRequest = { noteMenu = null },
                title = { Text(menuNote.name) },
                text = {
                    Column {
                        TextButton(
                            onClick = {
                                noteMenu = null
                                renameTarget = menuNote
                                renameText = menuNote.name
                                renameOpen = true
                            },
                        ) { Text("Rename") }
                        TextButton(
                            onClick = {
                                noteMenu = null
                                vm.duplicateNote(menuNote.id)
                            },
                        ) { Text("Duplicate") }
                        TextButton(
                            onClick = {
                                noteMenu = null
                                vm.exportNote(menuNote.id, asCalc = false)
                                scope.launch { drawerState.close() }
                            },
                        ) { Text("Export to .txt") }
                        TextButton(
                            onClick = {
                                noteMenu = null
                                vm.exportNote(menuNote.id, asCalc = true)
                                scope.launch { drawerState.close() }
                            },
                        ) { Text("Export to .calc") }
                        TextButton(
                            onClick = {
                                noteMenu = null
                                pendingDelete = menuNote
                            },
                        ) { Text("Delete") }
                    }
                },
                confirmButton = {},
            )
        }

        val doomed = pendingDelete
        if (doomed != null) {            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("Delete note?") },
                text = { Text("\"${doomed.name}\" and all its lines will be gone. This cannot be undone.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            vm.deleteNote(doomed.id)
                            pendingDelete = null
                            scope.launch { drawerState.close() }
                        },
                    ) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("Keep") }
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
    keyFontSp: TextUnit,
    keyHeight: Dp,
    hapticsOn: Boolean,
    soundOn: Boolean,
) {
    val haptics = LocalView.current
    val context = LocalContext.current
    val audio = remember(context) {
        context.getSystemService(android.media.AudioManager::class.java)
    }
    fun press(action: () -> Unit) {
        if (hapticsOn) {
            try {
                haptics.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            } catch (_: Throwable) {
            }
        }
        if (soundOn) {
            try {
                audio?.playSoundEffect(android.media.AudioManager.FX_KEY_CLICK)
            } catch (_: Throwable) {
            }
        }
        action()
    }
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
            Button(
                onClick = { press(action) },
                modifier = Modifier.height(keyHeight),
                contentPadding = PaddingValues(2.dp),
            ) {
                Text(label, fontSize = keyFontSp, maxLines = 1)
            }
        }
    }
}
