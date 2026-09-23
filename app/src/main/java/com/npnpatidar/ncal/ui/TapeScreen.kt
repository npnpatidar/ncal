package com.npnpatidar.ncal.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.LocalTextToolbar
import androidx.compose.foundation.text.TextToolbar
import androidx.compose.foundation.text.TextToolbarStatus
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextInputService
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.npnpatidar.ncal.settings.ThemeMode
import com.npnpatidar.ncal.storage.NotesRepository
import com.npnpatidar.ncal.tape.TapeEdit
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    // CALC mode: cursor follows keys/finger, keyboard never auto-shows.
    // Opening the drawer always dismisses the keyboard first (else it stays
    // up behind the drawer); closing it restores ABC state.
    LaunchedEffect(state.keypadMode, showSettings) {
        if (showSettings) return@LaunchedEffect
        if (state.keypadMode == KeypadMode.SYSTEM) {
            tapeFocus.requestFocus()
            keyboard?.show()
        } else if (state.keypadMode == KeypadMode.CALC) {
            tapeFocus.requestFocus()
        }
    }
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Open) {
            keyboard?.hide()
        } else if (state.keypadMode == KeypadMode.SYSTEM && !showSettings) {
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
    // Edge-to-edge status bar: dark icons on light theme, light icons on dark.
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? android.app.Activity)?.window
        if (window != null) {
            androidx.core.view.WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !dark
        }
    }
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.5f)) {
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
                            // ONE gesture handler per row: tap opens, long-press
                            // menus. (A nested clickable inside
                            // NavigationDrawerItem starved both, so the row is
                            // custom.) The ⋮ button opens the same menu.
                            val selected = note.id == state.noteId
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                                shape = MaterialTheme.shapes.large,
                                color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                                else Color.Transparent,
                            ) {
                                Row(
                                    modifier = Modifier.combinedClickable(
                                        onClick = {
                                            vm.selectNote(note.id)
                                            scope.launch { drawerState.close() }
                                        },
                                        onLongClick = { noteMenu = note },
                                    ).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        note.name,
                                        modifier = Modifier.weight(1f),
                                        color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                                    IconButton(onClick = { noteMenu = note }) {
                                        Icon(
                                            Icons.Filled.MoreVert,
                                            contentDescription = "Note options",
                                        )
                                    }
                                }
                            }
                        }
                    }
                    TextButton(onClick = { importLauncher.launch("*/*") }) {
                        Text("⇩ Import .calc / .txt")
                    }
                    TextButton(onClick = {
                        showSettings = true
                        scope.launch { drawerState.close() }
                    }) {
                        Icon(Icons.Filled.Settings, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Settings")
                    }
                    HorizontalDivider()
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
            val systemMode = state.keypadMode == KeypadMode.SYSTEM
            val st = state.settings
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
                    BottomPinnedControls(vm = vm, state = state)
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
                    Text(
                        "notepad — op amount comment per line",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Notepad. The cursor is drawn by hand (solid, always on —
                    // no blink cycle to miss) at the key/finger-driven offset.
                    // Tapping moves it freely; only ABC raises the keyboard.
                    val latestFont by rememberUpdatedState(st.tapeFontSp)
                    val cursorColor = MaterialTheme.colorScheme.primary
                    // No native selection handles anywhere: only our cursor ever
                    // shows, so the two can never detach from each other.
                    val noHandles = TextSelectionColors(
                        handleColor = Color.Transparent,
                        backgroundColor = LocalTextSelectionColors.current.backgroundColor,
                    )
                    // Outer scroll state: text and cursor scroll as one unit.
                    // Fresh per note; follows typing in CALC only while the
                    // caret is on the last line — mid-tape edits never yank
                    // the view to the bottom.
                    val listScroll = remember(state.noteId) { ScrollState(0) }
                    LaunchedEffect(state.tapeText, state.keypadMode) {
                        if (state.keypadMode == KeypadMode.CALC &&
                            TapeEdit.caretOnLastLine(state.tapeText, state.tapeSel.end)
                        ) {
                            listScroll.scrollTo(listScroll.maxValue)
                        }
                    }
                    // Blinking cursor like the reference: solid when unfocused.
                    val blinkAlpha by rememberInfiniteTransition(label = "cursor").animateFloat(
                        initialValue = 1f,
                        targetValue = 0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 1000),
                            repeatMode = RepeatMode.Restart,
                        ),
                        label = "cursorBlink",
                    )
                    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
                    var fieldFocused by remember { mutableStateOf(false) }
                    val tapeField: @Composable () -> Unit = {
                        BasicTextField(
                            value = TextFieldValue(state.tapeText, state.tapeSel),
                            onValueChange = vm::onTapeChange,
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max).focusRequester(tapeFocus)
                                .onFocusChanged { fieldFocused = it.isFocused }
                                .pinchZoom(
                                    getFont = { latestFont },
                                    onZoom = { vm.previewTapeFont(it) },
                                    onEnd = { vm.commitSettings() },
                                )
                                .cursorDragFollow(
                                    enabled = !systemMode,
                                    view = LocalView.current,
                                    getLayout = { textLayout },
                                    onCursor = vm::placeCursor,
                                ),
                            readOnly = state.keypadMode != KeypadMode.SYSTEM,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = st.tapeFontSp.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            keyboardOptions = if (systemMode) KeyboardOptions.Default
                            else KeyboardOptions(showKeyboardOnFocus = false),
                            cursorBrush = SolidColor(Color.Transparent),
                            onTextLayout = { textLayout = it },
                            decorationBox = { inner ->
                                // Plain box: border/padding live on the outer
                                // frame so this whole block scrolls as one.
                                Box {
                                    inner()
                                    val caret = state.tapeSel.end.coerceIn(0, state.tapeText.length)
                                    val rect = try {
                                        textLayout?.getCursorRect(caret)
                                    } catch (_: Throwable) {
                                        null
                                    }
                                    if (rect != null) {
                                        Canvas(modifier = Modifier.matchParentSize()) {
                                            val w = maxOf(rect.width, 2.dp.toPx())
                                            drawRect(
                                                color = cursorColor.copy(
                                                    alpha = if (fieldFocused) blinkAlpha else 1f,
                                                ),
                                                topLeft = Offset(rect.left, rect.top),
                                                size = Size(w, rect.height),
                                            )
                                        }
                                    }
                                }
                            },
                        )
                    }
                    // Fixed frame (border never scrolls); inside, text +
                    // cursor scroll together and pinch still works.
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f)
                            .border(
                                if (fieldFocused) 2.dp else 1.dp,
                                if (fieldFocused) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(4.dp),
                            )
                            .clip(RoundedCornerShape(4.dp)),
                    ) {
                        Column(
                            modifier = Modifier.verticalScroll(listScroll).padding(16.dp, 12.dp),
                        ) {
                            CompositionLocalProvider(
                                LocalTextSelectionColors provides noHandles,
                            ) {
                                if (systemMode) {
                                    tapeField()
                                } else {
                                    CompositionLocalProvider(LocalTextInputService provides null) {
                                        CompositionLocalProvider(LocalTextToolbar provides NoTextToolbar) {
                                            tapeField()
                                        }
                                    }
                                }
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
 * Strip + calculator keypad pinned to the bottom of the screen. Sitting in
 * Scaffold's bottomBar with imePadding, it stays visible and rides exactly
 * above the system keyboard (adjustPan in the manifest avoids double shift).
 */
@Composable
private fun BottomPinnedControls(vm: TapeViewModel, state: TapeUiState) {
    val st = state.settings
    Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
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
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
        }

        val landscape = LocalConfiguration.current.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val keyFont: TextUnit = st.keyFontSp.sp
        val keyConfigured: Dp =
            (if (landscape) st.keyHeightLandDp else st.keyHeightPortDp).dp
        // The keypad fits the space it gets: keys shrink to the
        // available height (capped so the tape keeps room) and
        // never scroll.
                    if (state.keypadMode == KeypadMode.CALC) {
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val cap = maxHeight * 0.6f
                            val rows = if (landscape) 2 else 5
                            val gap = 6.dp
                            val fitted = ((cap - gap * (rows - 1)) / rows).coerceAtLeast(20.dp)
                            KeypadGrid(
                                onDigit = vm::key,
                                onOp = vm::key,
                                onEquals = vm::equals,
                                onClear = vm::clear,
                                onUndo = vm::undo,
                                onBackspace = vm::backspace,
                                keyFontSp = keyFont,
                                keyHeight = minOf(keyConfigured, fitted),
                                hapticsOn = state.settings.haptics,
                                soundOn = state.settings.keySound,
                                landscape = landscape,
                            )
                        }
                    }
                }
            }
/**
 * CALC mode has its own cursor and keypad: the native floating menu
 * (Select all / Copy / Paste) must never pop up — press-hold is
 * cursor-follow, not a menu request. ABC/system mode keeps the native one.
 */
private object NoTextToolbar : TextToolbar {
    override val status: TextToolbarStatus = TextToolbarStatus.Hidden
    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = Unit
    override fun hide() = Unit
}
/**
 * Pinch-to-zoom (two-finger spread) for the notepad, like image zoom.
 * Anchor-based: when the second finger lands we record the finger distance
 * and the current font; every move maps back to that anchor, so there is no
 * drift and no per-frame persistence — [onZoom] previews live, [onEnd]
 * commits once on release. Single-finger scroll, cursor and selection pass
 * straight through to the text field. Never restarts mid-gesture (Unit key).
 */
private fun Modifier.pinchZoom(
    getFont: () -> Float,
    onZoom: (Float) -> Unit,
    onEnd: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var prevDist: Float? = null
        var anchorDist = 0f
        var anchorFont = 0f
        var active = false
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size < 2) {
                if (active) {
                    onEnd()
                    active = false
                }
                if (event.changes.all { !it.pressed }) break
                prevDist = null
                continue
            }
            val dist = (pressed[0].position - pressed[1].position).getDistance()
            val prev = prevDist
            prevDist = dist
            if (prev == null || prev <= 0f || dist <= 0f) {
                anchorDist = dist
                anchorFont = getFont()
                active = true
            } else if (anchorDist > 0f) {
                onZoom((anchorFont * dist / anchorDist).coerceIn(6f, 32f))
                active = true
            }
            pressed.forEach { it.consume() }
            if (event.changes.all { !it.pressed }) {
                onEnd()
                break
            }
        }
    }
}

/**
 * Press-hold-drag moves the hand-drawn cursor with the finger: tap still
 * jumps, a quick drag still scrolls, pinch still zooms.
 * - Down passes through untouched, so the tap/slop race is unchanged.
 * - Finger held still past long-press timeout → cursor mode (haptic tick);
 *   further moves set the caret from the text layout and are consumed so
 *   scroll and selection don't fight them.
 * - Finger moving past slop first, lifting early, or a second finger
 *   landing → back off entirely (scroll/selection/zoom proceed as before).
 * Never restarts mid-gesture (Unit key); disabled in ABC/system mode where
 * the native keyboard and handles own the finger.
 */
private fun Modifier.cursorDragFollow(
    enabled: Boolean,
    view: android.view.View,
    getLayout: () -> TextLayoutResult?,
    onCursor: (Int) -> Unit,
): Modifier = pointerInput(enabled) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // Race slop-breakthrough (scroll) against holding still (cursor).
        // null = held past the timeout; true = moved (scroll); false = bailed.
        val moved = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                if (ev.changes.all { !it.pressed }) return@withTimeoutOrNull false
                if (ev.changes.count { it.pressed } > 1) return@withTimeoutOrNull false
                val pastSlop = ev.changes.any {
                    it.pressed && (it.position - down.position).getDistance() > viewConfiguration.touchSlop
                }
                if (pastSlop) return@withTimeoutOrNull true
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
        if (moved != null) return@awaitEachGesture
        try {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        } catch (_: Throwable) {
        }
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = ev.changes.filter { it.pressed }
            if (pressed.isEmpty() || pressed.size > 1) break
            val off = try {
                getLayout()?.getOffsetForPosition(pressed[0].position)
            } catch (_: Throwable) {
                null
            }
            if (off != null) {
                onCursor(off)
                pressed.forEach { it.consume() }
            }
            if (ev.changes.all { !it.pressed }) break
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
    landscape: Boolean,
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
    // Triple: display label, optional vector icon, action. Display uses
    // glyphs (undo arrow, backspace) and x ÷ - symbols; inserted text stays ASCII.
    // Portrait is 4 columns x 5 rows; landscape spreads the same 20 keys over
    // 10 columns x 2 rows (digits on top, functions below) so the keyboard
    // stays short and the note keeps the room.
    val portraitKeys: List<KeyDef> = listOf(
        KeyDef(action = onClear, label = "AC"),
        KeyDef(action = onUndo, label = "↩"),
        KeyDef(action = onBackspace, label = "⌫"),
        KeyDef(action = { onOp("\n / ") }, label = "÷"),
        KeyDef(action = { onDigit("7") }, label = "7"),
        KeyDef(action = { onDigit("8") }, label = "8"),
        KeyDef(action = { onDigit("9") }, label = "9"),
        KeyDef(action = { onOp("\n * ") }, label = "×"),
        KeyDef(action = { onDigit("4") }, label = "4"),
        KeyDef(action = { onDigit("5") }, label = "5"),
        KeyDef(action = { onDigit("6") }, label = "6"),
        KeyDef(action = { onOp("\n - ") }, label = "−"),
        KeyDef(action = { onDigit("1") }, label = "1"),
        KeyDef(action = { onDigit("2") }, label = "2"),
        KeyDef(action = { onDigit("3") }, label = "3"),
        KeyDef(action = { onOp("\n + ") }, label = "+"),
        KeyDef(action = { onDigit("0") }, label = "0"),
        KeyDef(action = { onDigit(".") }, label = "."),
        KeyDef(action = { onOp("% ") }, label = "%"),
        KeyDef(action = onEquals, label = "="),
    )
    val landscapeKeys: List<KeyDef> = listOf(
        KeyDef(action = { onDigit("1") }, label = "1"),
        KeyDef(action = { onDigit("2") }, label = "2"),
        KeyDef(action = { onDigit("3") }, label = "3"),
        KeyDef(action = { onDigit("4") }, label = "4"),
        KeyDef(action = { onDigit("5") }, label = "5"),
        KeyDef(action = { onDigit("6") }, label = "6"),
        KeyDef(action = { onDigit("7") }, label = "7"),
        KeyDef(action = { onDigit("8") }, label = "8"),
        KeyDef(action = { onDigit("9") }, label = "9"),
        KeyDef(action = { onDigit("0") }, label = "0"),
        KeyDef(action = { onDigit(".") }, label = "."),
        KeyDef(action = { onOp("% ") }, label = "%"),
        KeyDef(action = { onOp("\n + ") }, label = "+"),
        KeyDef(action = { onOp("\n - ") }, label = "−"),
        KeyDef(action = { onOp("\n * ") }, label = "×"),
        KeyDef(action = { onOp("\n / ") }, label = "÷"),
        KeyDef(action = onEquals, label = "="),
        KeyDef(action = onClear, label = "AC"),
        KeyDef(action = onUndo, label = "↩"),
        KeyDef(action = onBackspace, label = "⌫"),
    )
    val keys = if (landscape) landscapeKeys else portraitKeys
    LazyVerticalGrid(
        columns = GridCells.Fixed(if (landscape) 10 else 4),
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        userScrollEnabled = false,
    ) {
        items(keys) { key ->
            Button(
                onClick = { press(key.action) },
                modifier = Modifier.height(keyHeight),
                contentPadding = PaddingValues(2.dp),
            ) {
                Text(key.label ?: "", fontSize = keyFontSp, maxLines = 1)
            }
        }
    }
}

private data class KeyDef(
    val action: () -> Unit,
    val label: String? = null,
)
