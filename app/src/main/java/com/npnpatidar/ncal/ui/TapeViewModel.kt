package com.npnpatidar.ncal.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.npnpatidar.ncal.export.CalcExport
import com.npnpatidar.ncal.logging.NcalLogger
import com.npnpatidar.ncal.settings.AppSettings
import com.npnpatidar.ncal.settings.NoteSort
import com.npnpatidar.ncal.settings.SettingsStore
import com.npnpatidar.ncal.storage.JournalEntry
import com.npnpatidar.ncal.storage.NoteJournal
import com.npnpatidar.ncal.storage.NotesRepository
import com.npnpatidar.ncal.storage.MediaStoreHelper
import com.npnpatidar.ncal.storage.SaveResult
import com.npnpatidar.ncal.tape.CalcFile
import com.npnpatidar.ncal.tape.CalcMeta
import com.npnpatidar.ncal.tape.Grouping
import com.npnpatidar.ncal.tape.TapeEdit
import com.npnpatidar.ncal.tape.TapeEvaluator
import com.npnpatidar.ncal.tape.TapeFormatter
import com.npnpatidar.ncal.tape.TapeLimits
import com.npnpatidar.ncal.tape.TapeLine
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class KeypadMode { CALC, SYSTEM, HIDDEN }

data class TapeUiState(
    val tapeText: String = " + 0\n",
    val tapeSel: TextRange = TextRange.Zero,
    val lineMarks: List<TapeFormatter.LineMark> = emptyList(),
    val totalText: String = "0",
    val grandText: String = "0",
    val memoryText: String = "0",
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val message: String? = null,
    val decimals: Int = 5,
    val settings: AppSettings = AppSettings(),
    val notes: List<NotesRepository.NoteMeta> = emptyList(),
    val noteId: String = "",
    val noteName: String = "",
    val keypadMode: KeypadMode = KeypadMode.CALC,
    val appVersion: String = "",
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

class TapeViewModel(app: Application) : AndroidViewModel(app) {

    private data class Snapshot(
        val noteId: String,
        val text: String,
        val selection: TextRange,
        val meta: CalcMeta,
        val settings: AppSettings,
        val textBytes: Int,
    )

    private data class Evaluation(
        val text: String,
        val selection: TextRange,
        val doc: com.npnpatidar.ncal.tape.TapeDoc,
        val eval: com.npnpatidar.ncal.tape.EvalResult,
    )

    private data class LoadedNote(
        val id: String,
        val result: CalcExport.ImportResult,
        val notes: List<NotesRepository.NoteMeta>,
    )

    private data class ImportedText(
        val text: String,
        val meta: CalcMeta,
        val settings: AppSettings,
        val warnings: List<String>,
        val errors: List<String>,
    )

    private data class LoadGuard(
        val switchVersion: Long,
        val editVersion: Long,
        val snapshot: Snapshot,
    )

    private val _state = MutableStateFlow(TapeUiState())
    val state: StateFlow<TapeUiState> = _state.asStateFlow()
    private val _shareIntents = MutableSharedFlow<android.content.Intent>(extraBufferCapacity = 1)
    val shareIntents: SharedFlow<android.content.Intent> = _shareIntents.asSharedFlow()
    private val repo = NotesRepository(app)
    private val settingsStore = SettingsStore(app)
    private val settingsMutex = Mutex()
    private val lifecycleMutex = Mutex()
    private val memoryMutex = Mutex()
    private val switchVersion = AtomicLong(0)
    private val editVersion = AtomicLong(0)
    private val notesVersion = AtomicLong(0)
    private val saveGeneration = AtomicLong(0)
    private var memory: BigDecimal = BigDecimal.ZERO
    private val undoStack = ArrayDeque<Snapshot>(50)
    private val redoStack = ArrayDeque<Snapshot>(50)
    private var undoBytes = 0L
    private var redoBytes = 0L
    private val suppressedSaveIds = mutableSetOf<String>()
    private var meta: CalcMeta = CalcMeta()
    private var decimals: Int = 5
    private var saveJob: Job? = null
    private var evaluationJob: Job? = null
    private var savePendingWhileSuppressed = false
    private val journalMutex = Mutex()
    private val journal: NoteJournal by lazy {
        NoteJournal(File(getApplication<Application>().filesDir, "journal"))
    }

    init {
        viewModelScope.launch {
            val startVersion = editVersion.get()
            val startSnapshot = snapshot()
            val loaded = withContext(Dispatchers.IO) { settingsStore.load() }
            decimals = loaded.decimals
            _state.update {
                it.copy(
                    appVersion = appVersion(app),
                    settings = loaded,
                    decimals = loaded.decimals,
                )
            }
            val initial = withContext(Dispatchers.IO) {
                repo.reconcileTombstones()
                journal.clearTemps()
                drainJournal(checkGenerations = false)
                var metas = repo.list()
                if (metas.isEmpty()) {
                    repo.create("Note 1")
                    metas = repo.list()
                }
                val id = repo.lastOpen()?.takeIf { candidate -> metas.any { it.id == candidate } }
                    ?: metas.firstOrNull()?.id
                if (id == null) null else {
                    val result = repo.load(id)
                    result?.let { LoadedNote(id, it, sortedNotes()) }
                }
            }
            if (initial == null) {
                _state.update { it.copy(message = "Unable to open a note") }
            } else if (commitLoaded(
                    initial,
                    loaded,
                    LoadGuard(switchVersion.get(), startVersion, startSnapshot),
                )
            ) {
                reevaluate("init")
            } else {
                _state.update { it.copy(notes = initial.notes) }
            }
        }
    }

    fun selectNote(id: String) {
        if (id == _state.value.noteId || id.isBlank()) return
        val version = switchVersion.incrementAndGet()
        notesVersion.incrementAndGet()
        viewModelScope.launch {
            lifecycleMutex.withLock {
                cancelPendingSave()
                val old = snapshot()
                val startVersion = editVersion.get()
                val saved = persist(old)
                if (saved is SaveResult.Failure) {
                    _state.update { it.copy(message = "Could not save the current note") }
                    return@withLock
                }
                if (editVersion.get() != startVersion || !sameNoteState(old)) {
                    cancelPendingSave()
                    if (!persistLatestOrWarn()) return@withLock
                    if (version == switchVersion.get()) {
                        _state.update { it.copy(message = "Note switch cancelled because the note changed") }
                    }
                    return@withLock
                }
                val loaded = loadNote(id)
                if (version != switchVersion.get()) return@withLock
                if (editVersion.get() != startVersion || !sameNoteState(old)) {
                    cancelPendingSave()
                    if (!persistLatestOrWarn()) return@withLock
                    if (version == switchVersion.get()) {
                        _state.update { it.copy(message = "Note switch cancelled because the note changed") }
                    }
                    return@withLock
                }
                if (loaded == null) {
                    _state.update { it.copy(message = "Could not open note") }
                    return@withLock
                }
                if (commitLoaded(loaded, _state.value.settings, LoadGuard(version, startVersion, old))) {
                    reevaluate("switch")
                }
            }
        }
    }

    fun createNote() {
        val version = switchVersion.incrementAndGet()
        notesVersion.incrementAndGet()
        viewModelScope.launch {
            lifecycleMutex.withLock {
                cancelPendingSave()
                val old = snapshot()
                val startVersion = editVersion.get()
                val saved = persist(old)
                if (saved is SaveResult.Failure) {
                    _state.update { it.copy(message = "Could not save the current note") }
                    return@withLock
                }
                if (editVersion.get() != startVersion || !sameNoteState(old)) {
                    cancelPendingSave()
                    if (!persistLatestOrWarn()) return@withLock
                }
                val created = withContext(Dispatchers.IO) {
                    val name = "Note ${repo.list().size + 1}"
                    repo.create(name)?.let { id -> repo.load(id)?.let { LoadedNote(id, it, sortedNotes()) } }
                }
                if (version != switchVersion.get()) return@withLock
                if (editVersion.get() != startVersion || !sameNoteState(old)) {
                    cancelPendingSave()
                    if (!persistLatestOrWarn()) return@withLock
                    val notes = withContext(Dispatchers.IO) { sortedNotes() }
                    _state.update {
                        it.copy(notes = notes, message = "Note creation cancelled because the note changed")
                    }
                    return@withLock
                }
                if (created == null) {
                    _state.update { it.copy(message = "Could not create note") }
                    return@withLock
                }
                if (commitLoaded(created, _state.value.settings, LoadGuard(version, startVersion, old))) {
                    reevaluate("new")
                }
            }
        }
    }

    fun deleteNote(id: String) {
        if (id.isBlank()) return
        val activeAtRequest = id == _state.value.noteId
        val version = switchVersion.incrementAndGet()
        val notesRequest = notesVersion.incrementAndGet()
        if (activeAtRequest) suppressedSaveIds += id
        viewModelScope.launch {
            lifecycleMutex.withLock {
                var stableSnapshot = snapshot()
                var stableVersion = editVersion.get()
                if (activeAtRequest) {
                    var stable = false
                    for (attempt in 1..4) {
                        cancelPendingSave()
                        stableSnapshot = snapshot()
                        stableVersion = editVersion.get()
                        val saved = persist(stableSnapshot)
                        if (saved is SaveResult.Failure) {
                            suppressedSaveIds -= id
                            _state.update { it.copy(message = "Could not save the current note") }
                            return@withLock
                        }
                        cancelPendingSave()
                        if (editVersion.get() == stableVersion && sameNoteState(stableSnapshot)) {
                            stable = true
                            break
                        }
                    }
                    if (!stable) {
                        cancelPendingSave()
                        persist(snapshot())
                        suppressedSaveIds -= id
                        _state.update { it.copy(message = "Note changed during delete; deletion cancelled") }
                        return@withLock
                    }
                }
                val deleted = withContext(Dispatchers.IO) { repo.delete(id) }
                if (deleted) journal.discardNote(id)
                if (!deleted) {
                    if (activeAtRequest) {
                        cancelPendingSave()
                        if (!persistLatestOrWarn()) {
                            suppressedSaveIds -= id
                            return@withLock
                        }
                    }
                    suppressedSaveIds -= id
                    _state.update { it.copy(message = "Delete failed; note was kept") }
                    return@withLock
                }
                if (activeAtRequest) {
                    val replacement = withContext(Dispatchers.IO) {
                        val notes = repo.list()
                        val next = notes.firstOrNull()
                        next?.let { repo.load(it.id)?.let { loaded -> LoadedNote(it.id, loaded, notes) } }
                            ?: repo.create("Note 1")?.let { newId ->
                                repo.load(newId)?.let { loaded -> LoadedNote(newId, loaded, repo.list()) }
                            }
                    }
                    if (replacement != null) {
                        val guard = LoadGuard(version, stableVersion, stableSnapshot)
                        if (commitLoaded(replacement, _state.value.settings, guard)) {
                            suppressedSaveIds -= id
                            reevaluate("delete-switch")
                        } else {
                            var preserved = false
                            for (attempt in 1..3) {
                                if (preserved) break
                                val kept = snapshot()
                                val keptVersion = editVersion.get()
                                val keptSave = withContext(Dispatchers.IO) {
                                    repo.save(replacement.id, kept.text, kept.meta.copy(uuid = replacement.id))
                                }
                                if (keptSave !is SaveResult.Success) break
                                val reloaded = withContext(Dispatchers.IO) {
                                    repo.load(replacement.id)?.let { loaded ->
                                        LoadedNote(replacement.id, loaded, sortedNotes())
                                    }
                                }
                                if (reloaded != null && commitLoaded(
                                        reloaded,
                                        _state.value.settings,
                                        LoadGuard(version, keptVersion, kept),
                                    )
                                ) {
                                    preserved = true
                                }
                            }
                            suppressedSaveIds -= id
                            if (preserved) reevaluate("delete-switch")
                            else _state.update {
                                it.copy(message = "Delete completed, but concurrent edits could not be preserved")
                            }
                        }
                    } else {
                        suppressedSaveIds -= id
                        if (version == switchVersion.get()) _state.update { it.copy(message = "No notes available") }
                    }
                } else {
                    val notes = withContext(Dispatchers.IO) { sortedNotes() }
                    _state.update {
                        if (notesRequest == notesVersion.get()) it.copy(notes = notes) else it
                    }
                }
            }
        }
    }

    fun renameNote(name: String) = renameNoteById(_state.value.noteId, name)

    fun renameNoteById(id: String, name: String) {
        val clean = name.trim().take(64)
        if (id.isBlank() || clean.isBlank()) return
        val notesRequest = notesVersion.incrementAndGet()
        viewModelScope.launch {
            val renamed = withContext(Dispatchers.IO) { repo.rename(id, clean) }
            val notes = if (renamed) withContext(Dispatchers.IO) { sortedNotes() } else emptyList()
            _state.update {
                if (notesRequest != notesVersion.get()) it
                else if (!renamed) it.copy(message = "Rename failed")
                else it.copy(notes = notes, noteName = if (id == it.noteId) clean else it.noteName)
            }
        }
    }

    fun duplicateNote(id: String) {
        if (id.isBlank()) return
        val notesRequest = notesVersion.incrementAndGet()
        viewModelScope.launch {
            lifecycleMutex.withLock {
                val old = snapshot()
                val startVersion = editVersion.get()
                if (old.noteId == id) {
                    cancelPendingSave()
                    val saved = persist(old)
                    if (saved is SaveResult.Failure) {
                        _state.update { it.copy(message = "Could not save the current note") }
                        return@withLock
                    }
                    if (editVersion.get() != startVersion || !sameNoteState(old)) {
                        if (!persistLatestOrWarn()) return@withLock
                    }
                }
                val result = withContext(Dispatchers.IO) { repo.duplicate(id) }
                val notes = withContext(Dispatchers.IO) { sortedNotes() }
                _state.update {
                    if (notesRequest != notesVersion.get()) it else it.copy(
                        notes = notes,
                        message = if (result != null) "Duplicated note" else "Duplicate failed",
                    )
                }
            }
        }
    }

    fun exportNote(id: String, asCalc: Boolean) {
        val s = _state.value
        val current = id == s.noteId
        val name = s.notes.firstOrNull { it.id == id }?.name?.ifBlank { "ncal" } ?: "ncal"
        val liveText = if (current) s.tapeText else null
        val liveMeta = if (current) meta else null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val text = liveText ?: repo.loadRaw(id) ?: return@withContext null
                if (asCalc) CalcExport.exportCalcResult(getApplication(), name, text, liveMeta)
                else CalcExport.exportTxtResult(getApplication(), name, text, liveMeta)
            }
            _state.update { state ->
                state.copy(message = when {
                    result == null -> "Export failed"
                    !result.successful -> result.errors.firstOrNull() ?: "Fix calculation errors before exporting"
                    else -> "Saved $name.${if (asCalc) "calc" else "txt"}"
                })
            }
        }
    }

    fun shareNote(id: String, asCalc: Boolean) {
        val s = _state.value
        val current = id == s.noteId
        val name = s.notes.firstOrNull { it.id == id }?.name?.ifBlank { "ncal" } ?: "ncal"
        val liveText = if (current) s.tapeText else null
        val liveMeta = if (current) meta else null
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val text = liveText ?: repo.loadRaw(id) ?: return@withContext null
                    if (asCalc) CalcExport.exportCalcResult(getApplication(), name, text, liveMeta)
                    else CalcExport.exportTxtResult(getApplication(), name, text, liveMeta)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                null
            }
            if (result?.successful == true && result.uri != null) {
                _shareIntents.emit(
                    MediaStoreHelper.createShareIntent(
                        result.uri,
                        if (asCalc) "application/octet-stream" else "text/plain",
                    ),
                )
            }
            _state.update {
                it.copy(message = when {
                    result == null -> "Share failed"
                    !result.successful -> result.errors.firstOrNull() ?: "Fix calculation errors before sharing"
                    else -> "Shared $name.${if (asCalc) "calc" else "txt"}"
                })
            }
        }
    }

    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val app = getApplication<Application>()
        val fallbackMeta = meta.copy(decimals = _state.value.settings.decimals)
        val before = snapshot()
        val startVersion = editVersion.get()
        val notesRequest = notesVersion.incrementAndGet()
        viewModelScope.launch {
            var ok = 0
            var firstMeta: CalcMeta? = null
            val failures = mutableListOf<String>()
            for (uri in uris) {
                val imported = withContext(Dispatchers.IO) {
                    try {
                        val name = displayNameOf(app, uri)
                        if (name != null && name.substringAfterLast('.', "").lowercase() !in setOf("calc", "txt")) return@withContext null
                        val text = app.contentResolver.openInputStream(uri)?.use { readBounded(it) }
                            ?: return@withContext null
                        val id = repo.importDoc(name ?: "Imported", text, fallbackMeta) ?: return@withContext null
                        if (firstMeta == null && CalcFile.hasHeader(text)) firstMeta = CalcFile.parse(text, fallbackMeta).meta
                        id to true
                    } catch (_: Throwable) {
                        null
                    }
                }
                if (imported != null) {
                    ok++
                } else {
                    failures += "One file could not be imported"
                }
            }
            val canPublish = notesRequest == notesVersion.get() &&
                editVersion.get() == startVersion && sameNoteState(before)
            if (canPublish) {
                val notes = withContext(Dispatchers.IO) { sortedNotes() }
                val first = firstMeta
                if (first != null) {
                    val currentSettings = _state.value.settings
                    updateSettings(currentSettings.copy(decimals = first.decimals))
                }
                _state.update {
                    it.copy(
                        notes = notes,
                        message = when {
                            ok > 0 && failures.isEmpty() ->
                                "Imported $ok note${if (ok == 1) "" else "s"}"
                            ok > 0 ->
                                "Imported $ok note${if (ok == 1) "" else "s"}; " +
                                    "${failures.size} file${if (failures.size == 1) "" else "s"} failed"
                            else -> failures.firstOrNull() ?: "Import failed"
                        },
                    )
                }
            }
        }
    }

    fun updateSettings(next: AppSettings) {
        val base = _state.value.settings
        val requested = next.sanitized()
        viewModelScope.launch {
            lifecycleMutex.withLock {
                val current = _state.value.settings
                if (requested == current) return@withLock
                val sanitized = mergeSettings(base, requested, current)
                val history = snapshot()
                val startVersion = editVersion.get()
                saveSettings(sanitized)
                val contentUnchanged = editVersion.get() == startVersion &&
                    _state.value.noteId == history.noteId && _state.value.tapeText == history.text
                val formatChanged = sanitized.decimals != current.decimals ||
                    sanitized.indent != current.indent || sanitized.grouping != current.grouping
                _state.update { it.copy(settings = sanitized) }
                markEdited()
                pushUndo(history)
                if (formatChanged) {
                    decimals = sanitized.decimals
                    meta = history.meta.copy(decimals = decimals)
                    if (contentUnchanged) {
                        val formatted = try {
                            withContext(Dispatchers.IO) {
                                TapeFormatter.pretty(
                                    history.text,
                                    decimals,
                                    sanitized.indent,
                                    sanitized.grouping,
                                    history.meta.copy(decimals = decimals),
                                )
                            }
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            _state.update { it.copy(message = t.message ?: "Could not format note") }
                            null
                        }
                        if (formatted != null && _state.value.noteId == history.noteId &&
                            _state.value.tapeText == history.text && formatted != history.text
                        ) {
                            _state.update { it.copy(tapeText = formatted, decimals = decimals) }
                            markEdited()
                            reevaluate("settings")
                            scheduleSave()
                        }
                    } else {
                        reevaluate("settings-stale")
                    }
                }
                if (sanitized.noteSort != current.noteSort) {
                    val notes = withContext(Dispatchers.IO) { sortedNotes() }
                    _state.update { it.copy(notes = notes) }
                }
            }
        }
    }

    fun previewTapeFont(sp: Float) {
        val clamped = sp.takeIf { it.isFinite() }?.coerceIn(6f, 32f) ?: 16f
        _state.update { it.copy(settings = it.settings.copy(tapeFontSp = clamped)) }
    }

    fun commitSettings() {
        val settings = _state.value.settings.sanitized()
        viewModelScope.launch { saveSettings(settings) }
    }

    fun onTapeChange(value: TextFieldValue) {
        if (value.text.length > TapeLimits.MAX_INPUT_CHARS) {
            _state.update { it.copy(message = "Tape exceeds the supported size") }
            return
        }
        val previous = _state.value
        if (previous.keypadMode == KeypadMode.SYSTEM && value.text == "${previous.tapeText}\n") {
            equals()
            return
        }
        if (value.text == previous.tapeText) {
            if (value.selection != previous.tapeSel) _state.update { it.copy(tapeSel = value.selection) }
            return
        }
        pushUndo(snapshot())
        _state.update { it.copy(tapeText = value.text, tapeSel = value.selection) }
        markEdited()
        reevaluate("edit")
        scheduleSave()
    }

    fun placeCursor(offset: Int) {
        val current = _state.value
        val cursor = offset.coerceIn(0, current.tapeText.length)
        if (current.tapeSel.start != cursor || current.tapeSel.end != cursor) {
            _state.update { it.copy(tapeSel = TextRange(cursor)) }
        }
    }

    fun key(token: String) {
        val current = _state.value
        if (current.tapeText.length + token.length > TapeLimits.MAX_INPUT_CHARS) {
            _state.update { it.copy(message = "Tape exceeds the supported size") }
            return
        }
        val (next, cursor) = TapeEdit.insertToken(current.tapeText, current.tapeSel.start, current.tapeSel.end, token)
        if (next == current.tapeText) {
            _state.update { it.copy(tapeSel = TextRange(cursor)) }
            return
        }
        pushUndo(snapshot())
        _state.update { it.copy(tapeText = next, tapeSel = TextRange(cursor)) }
        markEdited()
        reevaluate("key")
        scheduleSave()
    }

    fun backspace() {
        val current = _state.value
        val (next, cursor) = TapeEdit.deleteAt(current.tapeText, current.tapeSel.start, current.tapeSel.end)
        if (next == current.tapeText) return
        pushUndo(snapshot())
        _state.update { it.copy(tapeText = next, tapeSel = TextRange(cursor)) }
        markEdited()
        reevaluate("backspace")
        scheduleSave()
    }

    fun newLine() {
        val current = _state.value
        if (current.tapeText.length + 2 > TapeLimits.MAX_INPUT_CHARS) {
            _state.update { it.copy(message = "Tape exceeds the supported size") }
            return
        }
        pushUndo(snapshot())
        val next = current.tapeText.trimEnd() + "\n "
        _state.update { it.copy(tapeText = next, tapeSel = TextRange(next.length)) }
        markEdited()
        reevaluate("newline")
        scheduleSave()
    }

    fun equals() {
        val before = snapshot()
        val startVersion = editVersion.get()
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { calculateEquals(before) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                EqualsResult(null, t.message ?: "Could not total note")
            }
            if (editVersion.get() != startVersion || _state.value.tapeText != before.text ||
                _state.value.noteId != before.noteId || _state.value.settings != before.settings
            ) return@launch
            when {
                result.noop -> Unit
                result.next == null -> _state.update { it.copy(message = result.message) }
                else -> {
                    pushUndo(before)
                    _state.update { it.copy(tapeText = result.next, tapeSel = TextRange(result.next.length)) }
                    markEdited()
                    reevaluate("equals")
                    scheduleSave()
                }
            }
        }
    }

    fun clear() {
        pushUndo(snapshot())
        _state.update { it.copy(tapeText = "", tapeSel = TextRange.Zero) }
        markEdited()
        reevaluate("clear")
        scheduleSave()
    }

    fun undo() {
        viewModelScope.launch {
            lifecycleMutex.withLock {
                val previous = undoStack.removeLastOrNull() ?: return@withLock
                undoBytes = (undoBytes - previous.textBytes).coerceAtLeast(0L)
                val current = snapshot()
                redoStack.addLast(current)
                redoBytes += current.textBytes
                restore(previous)
                saveSettings(previous.settings)
                markEdited()
                reevaluate("undo")
                scheduleSave()
            }
        }
    }

    fun redo() {
        viewModelScope.launch {
            lifecycleMutex.withLock {
                val next = redoStack.removeLastOrNull() ?: return@withLock
                redoBytes = (redoBytes - next.textBytes).coerceAtLeast(0L)
                val current = snapshot()
                undoStack.addLast(current)
                undoBytes += current.textBytes
                restore(next)
                saveSettings(next.settings)
                markEdited()
                reevaluate("redo")
                scheduleSave()
            }
        }
    }

    fun memoryAdd() = memoryFromTotal { current, total -> current.add(total, TapeEvaluator.MC) }
    fun memorySub() = memoryFromTotal { current, total -> current.subtract(total, TapeEvaluator.MC) }
    fun memoryClear() = memoryOp { BigDecimal.ZERO }

    fun memoryRecall() {
        val current = _state.value
        if (current.tapeText.length + 32 > TapeLimits.MAX_INPUT_CHARS) {
            _state.update { it.copy(message = "Tape exceeds the supported size") }
            return
        }
        pushUndo(snapshot())
        val line = CalcFile.formatEntry('+', memory, false, "MR", meta)
        val next = current.tapeText.trimEnd() + "\n$line\n"
        _state.update { it.copy(tapeText = next, tapeSel = TextRange(next.length)) }
        markEdited()
        reevaluate("memory-recall")
        scheduleSave()
    }

    fun setKeypadMode(mode: KeypadMode) {
        _state.update { it.copy(keypadMode = mode) }
    }

    fun setDecimals(value: Int) {
        updateSettings(_state.value.settings.copy(decimals = value))
    }

    fun exportCalc() = exportCurrent(true)

    fun exportTxt() = exportCurrent(false)

    fun importText(text: String) {
        if (text.length > TapeLimits.MAX_INPUT_CHARS) {
            _state.update { it.copy(message = "Import exceeds the supported size") }
            return
        }
        viewModelScope.launch {
            lifecycleMutex.withLock {
                cancelPendingSave()
                val before = snapshot()
                val startVersion = editVersion.get()
                val imported = try {
                    withContext(Dispatchers.IO) {
                        val fallback = before.meta.copy(decimals = before.settings.decimals)
                        val result = CalcExport.importToTapeText(text, fallback)
                        val hasValidHeader = CalcFile.hasHeader(text)
                        val importedDecimals = if (hasValidHeader) {
                            TapeLimits.safeDecimals(result.meta.decimals)
                        } else {
                            before.settings.decimals
                        }
                        val importedMeta = result.meta.copy(
                            uuid = before.meta.uuid,
                            decimals = importedDecimals,
                        )
                        val updatedSettings = before.settings.copy(decimals = importedDecimals)
                        val formatted = TapeFormatter.pretty(
                            result.tapeText,
                            importedDecimals,
                            updatedSettings.indent,
                            updatedSettings.grouping,
                            importedMeta,
                        )
                        ImportedText(formatted, importedMeta, updatedSettings, result.warnings, result.errors)
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    _state.update { it.copy(message = t.message ?: "Import failed") }
                    return@withLock
                }
                if (editVersion.get() != startVersion || !sameNoteState(before)) {
                    _state.update { it.copy(message = "Import cancelled because the note changed") }
                    return@withLock
                }
                pushUndo(before)
                decimals = imported.settings.decimals
                meta = imported.meta
                val importedSelection = offsetAt(imported.text, imported.meta.caretLine, imported.meta.caretOffset)
                _state.update {
                    it.copy(
                        tapeText = imported.text,
                        tapeSel = TextRange(importedSelection),
                        settings = imported.settings,
                        decimals = imported.settings.decimals,
                        warnings = imported.warnings,
                        errors = imported.errors,
                    )
                }
                markEdited()
                val importedRevision = editVersion.get()
                saveSettings(imported.settings)
                if (editVersion.get() != importedRevision) {
                    saveSettings(_state.value.settings.sanitized())
                }
                reevaluate("import")
                scheduleSave()
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun flushNow() {
        val current = snapshot()
        val generation = saveGeneration.incrementAndGet()
        saveJob?.cancel()
        saveJob = null
        if (current.noteId in suppressedSaveIds) return
        val entry = JournalEntry(current.noteId, generation, current.text, current.meta)
        if (journal.stage(entry, sync = true)) {
            viewModelScope.launch(Dispatchers.IO) { drainJournal(checkGenerations = true) }
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                val result = persist(current, generation)
                if (result is SaveResult.Failure) _state.update { it.copy(message = "Background save failed") }
            }
        }
    }

    private suspend fun drainJournal(checkGenerations: Boolean) {
        journalMutex.withLock {
            val plan = journal.planReplay(
                checkGenerations,
                isDeleted = { repo.isDeleted(it) },
                isCurrentGeneration = { it == saveGeneration.get() },
            )
            plan.discard.forEach { journal.delete(it) }
            for (planned in plan.apply) {
                val result = repo.saveIfCurrent(
                    planned.entry.noteId,
                    planned.entry.text,
                    planned.entry.meta,
                ) {
                    !checkGenerations || planned.entry.generation == saveGeneration.get()
                }
                if (result is SaveResult.Success) journal.delete(planned.file)
                else if (result is SaveResult.Failure) break
            }
        }
    }

    private fun exportCurrent(asCalc: Boolean) {
        val current = _state.value
        val exportMeta = meta
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (asCalc) CalcExport.exportCalcResult(getApplication(), current.noteName.ifBlank { "ncal" }, current.tapeText, exportMeta)
                else CalcExport.exportTxtResult(getApplication(), current.noteName.ifBlank { "ncal" }, current.tapeText, exportMeta)
            }
            _state.update {
                it.copy(message = when {
                    !result.successful -> result.errors.firstOrNull() ?: "Export failed"
                    else -> "Saved .${if (asCalc) "calc" else "txt"}"
                })
            }
        }
    }

    private data class EqualsResult(val next: String?, val message: String? = null, val noop: Boolean = false)

    private fun calculateEquals(before: Snapshot): EqualsResult {
        val current = before.text.trimEnd()
        val probe = CalcFile.parse(current, before.meta)
        if (probe.lines.none { it is TapeLine.Entry }) return EqualsResult(null, "Nothing to total")
        if (!TapeEvaluator.hasOpenEntries(probe.lines)) {
            val lastRaw = current.lines().lastOrNull { it.isNotBlank() } ?: ""
            if (!CalcFile.isBareOpLine(lastRaw)) {
                if (before.text.endsWith("\n\n")) return EqualsResult(null, noop = true)
                return EqualsResult(TapeEdit.openFreshSection(current))
            }
        }
        val doc = CalcFile.parse(current, before.meta)
        val evalDecimals = before.settings.decimals
        val eval = TapeEvaluator.evaluate(doc.lines, evalDecimals)
        val balance = CalcFile.formatBalance(eval.openTotal, "", before.meta.copy(decimals = evalDecimals))
        val next = TapeFormatter.pretty(
            "$current\n${CalcFile.SEPARATOR}\n$balance",
            evalDecimals,
            before.settings.indent,
            before.settings.grouping,
            before.meta,
        )
        return EqualsResult(next)
    }

    private fun reevaluate(reason: String) {
        val current = snapshot()
        evaluationJob?.cancel()
        evaluationJob = viewModelScope.launch {
            val evaluated = try {
                withContext(Dispatchers.IO) { evaluate(current) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (_state.value.tapeText == current.text && _state.value.noteId == current.noteId &&
                    _state.value.settings == current.settings
                ) {
                    _state.update { it.copy(errors = listOf(t.message ?: "Could not evaluate note")) }
                }
                return@launch
            }
            if (_state.value.tapeText != current.text || _state.value.noteId != current.noteId ||
                _state.value.settings != current.settings
            ) return@launch
            val sectionIndex = TapeEvaluator.sectionIndexForLine(evaluated.doc.lines, lineIndexAt(evaluated.text, evaluated.selection.start))
            val sectionTotal = evaluated.eval.sectionTotals.getOrElse(sectionIndex) { evaluated.eval.grandTotal }
            _state.update {
                it.copy(
                    tapeText = evaluated.text,
                    tapeSel = evaluated.selection,
                    lineMarks = TapeFormatter.markLines(evaluated.doc, evaluated.eval),
                    warnings = evaluated.doc.warnings,
                    totalText = fmt(sectionTotal, current.settings.decimals),
                    grandText = fmt(evaluated.eval.grandTotal, current.settings.decimals),
                    errors = evaluated.eval.errors,
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                )
            }
            NcalLogger.d("Tape", "evaluated reason=$reason lines=${evaluated.doc.lines.size}")
        }
    }

    private fun evaluate(current: Snapshot): Evaluation {
        val evalDecimals = current.settings.decimals
        val doc = CalcFile.parse(current.text, current.meta)
        val eval = TapeEvaluator.evaluate(doc.lines, evalDecimals)
        var text = current.text
        var selection = current.selection
        var finalDoc = doc
        var finalEval = eval
        TapeFormatter.patchBalances(
            text,
            doc,
            eval,
            evalDecimals,
            current.settings.indent,
            current.settings.grouping,
            doc.meta.decSep,
            doc.meta.thouSep,
        )
            ?.let { patched ->
                val reparsed = CalcFile.parse(patched, current.meta)
                text = patched
                selection = mapCursor(current.selection, current.text, patched)
                finalDoc = reparsed
                finalEval = TapeEvaluator.evaluate(reparsed.lines, evalDecimals)
            }
        return Evaluation(text, selection, finalDoc, finalEval)
    }

    private suspend fun commitLoaded(
        loaded: LoadedNote,
        settings: AppSettings,
        guard: LoadGuard? = null,
    ): Boolean {
        if (guard != null && !loadGuardCurrent(guard)) {
            _state.update { it.copy(message = "Note load cancelled because the note changed") }
            return false
        }
        val targetDecimals = TapeLimits.safeDecimals(settings.decimals)
        val formatted = try {
            withContext(Dispatchers.IO) {
                TapeFormatter.pretty(
                    loaded.result.tapeText,
                    targetDecimals,
                    settings.indent,
                    settings.grouping,
                    loaded.result.meta.copy(decimals = targetDecimals),
                )
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _state.update { it.copy(message = t.message ?: "Could not format note") }
            return false
        }
        if (guard != null && !loadGuardCurrent(guard)) {
            _state.update { it.copy(message = "Note load cancelled because the note changed") }
            return false
        }
        decimals = targetDecimals
        meta = loaded.result.meta.copy(decimals = targetDecimals)
        val selection = offsetAt(formatted, meta.caretLine, meta.caretOffset)
        _state.update {
            it.copy(
                tapeText = formatted,
                tapeSel = TextRange(selection),
                notes = loaded.notes,
                noteId = loaded.id,
                noteName = loaded.notes.firstOrNull { note -> note.id == loaded.id }?.name ?: "Note",
                decimals = targetDecimals,
                warnings = loaded.result.warnings,
                errors = loaded.result.errors,
                canUndo = false,
                canRedo = false,
            )
        }
        undoStack.clear()
        redoStack.clear()
        undoBytes = 0L
        redoBytes = 0L
        markEdited()
        if (savePendingWhileSuppressed) {
            savePendingWhileSuppressed = false
            scheduleSave()
        }
        viewModelScope.launch(Dispatchers.IO) { repo.setLastOpen(loaded.id) }
        return true
    }

    private suspend fun loadNote(id: String): LoadedNote? = withContext(Dispatchers.IO) {
        val result = repo.load(id) ?: return@withContext null
        LoadedNote(id, result, sortedNotes())
    }

    private fun sortedNotes(): List<NotesRepository.NoteMeta> {
        val metas = repo.list()
        return when (_state.value.settings.noteSort) {
            NoteSort.DATE -> metas.sortedByDescending { repo.lastModified(it.id) }
            NoteSort.NAME_ASC -> metas.sortedBy { it.name.lowercase() }
            NoteSort.NAME_DESC -> metas.sortedByDescending { it.name.lowercase() }
        }
    }

    private fun markEdited() {
        editVersion.incrementAndGet()
    }

    private fun sameNoteState(snapshot: Snapshot): Boolean {
        val current = _state.value
        return current.noteId == snapshot.noteId && current.tapeText == snapshot.text &&
            current.settings == snapshot.settings && meta == snapshot.meta
    }

    private fun loadGuardCurrent(guard: LoadGuard): Boolean =
        switchVersion.get() == guard.switchVersion && editVersion.get() == guard.editVersion &&
            sameNoteState(guard.snapshot)

    private suspend fun cancelPendingSave() {
        saveJob?.cancelAndJoin()
        saveJob = null
    }

    private fun snapshot(): Snapshot {
        val position = lineColumn(_state.value.tapeText, _state.value.tapeSel.start)
        meta = meta.copy(caretLine = position.first, caretOffset = position.second)
        return Snapshot(
            _state.value.noteId,
            _state.value.tapeText,
            _state.value.tapeSel,
            meta,
            _state.value.settings,
            TapeLimits.utf8Size(_state.value.tapeText).toInt(),
        )
    }

    private fun mergeSettings(
        base: AppSettings,
        requested: AppSettings,
        current: AppSettings,
    ): AppSettings = current.copy(
        themeMode = if (requested.themeMode != base.themeMode) requested.themeMode else current.themeMode,
        decimals = if (requested.decimals != base.decimals) requested.decimals else current.decimals,
        indent = if (requested.indent != base.indent) requested.indent else current.indent,
        grouping = if (requested.grouping != base.grouping) requested.grouping else current.grouping,
        tapeFontSp = if (requested.tapeFontSp != base.tapeFontSp) requested.tapeFontSp else current.tapeFontSp,
        keyFontSp = if (requested.keyFontSp != base.keyFontSp) requested.keyFontSp else current.keyFontSp,
        keyHeightPortDp = if (requested.keyHeightPortDp != base.keyHeightPortDp) requested.keyHeightPortDp else current.keyHeightPortDp,
        keyHeightLandDp = if (requested.keyHeightLandDp != base.keyHeightLandDp) requested.keyHeightLandDp else current.keyHeightLandDp,
        haptics = if (requested.haptics != base.haptics) requested.haptics else current.haptics,
        keySound = if (requested.keySound != base.keySound) requested.keySound else current.keySound,
        noteSort = if (requested.noteSort != base.noteSort) requested.noteSort else current.noteSort,
    ).sanitized()

    private suspend fun saveSettings(settings: AppSettings) {
        withContext(Dispatchers.IO) {
            settingsMutex.withLock { settingsStore.save(settings) }
        }
    }

    private suspend fun persistLatestOrWarn(): Boolean {
        val result = persist(snapshot())
        if (result is SaveResult.Failure) {
            _state.update { it.copy(message = "Could not save latest changes") }
            return false
        }
        return true
    }

    private suspend fun persist(snapshot: Snapshot, generation: Long? = null): SaveResult =
        withContext(Dispatchers.IO) {
            if (snapshot.text.isBlank() && snapshot.meta.uuid.isBlank()) {
                return@withContext SaveResult.Failure("invalid note")
            }
            if (generation != null) {
                repo.saveIfCurrent(snapshot.noteId, snapshot.text, snapshot.meta) {
                    generation == saveGeneration.get()
                }
            } else {
                repo.save(snapshot.noteId, snapshot.text, snapshot.meta)
            }
        }

    private fun scheduleSave() {
        val current = snapshot()
        if (current.noteId in suppressedSaveIds) {
            savePendingWhileSuppressed = true
            return
        }
        savePendingWhileSuppressed = false
        val generation = saveGeneration.incrementAndGet()
        journal.stage(
            JournalEntry(current.noteId, generation, current.text, current.meta),
            sync = false,
        )
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(800)
            if (generation != saveGeneration.get()) return@launch
            if (current.noteId in suppressedSaveIds) return@launch
            val result = persist(current, generation)
            if (result is SaveResult.Success) {
                journal.discardUpTo(current.noteId, generation)
            } else if (result is SaveResult.Failure) {
                _state.update { it.copy(message = "Autosave failed") }
            }
        }
    }

    private fun restore(snapshot: Snapshot) {
        meta = snapshot.meta
        _state.update {
            it.copy(
                tapeText = snapshot.text,
                tapeSel = snapshot.selection,
                settings = snapshot.settings,
                decimals = snapshot.settings.decimals,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
            )
        }
    }

    private fun memoryOp(operation: (BigDecimal) -> BigDecimal) {
        viewModelScope.launch {
            memoryMutex.withLock {
                memory = operation(memory)
                _state.update { it.copy(memoryText = fmt(memory)) }
            }
        }
    }

    private fun memoryFromTotal(operation: (BigDecimal, BigDecimal) -> BigDecimal) {
        val current = snapshot()
        viewModelScope.launch {
            val total = withContext(Dispatchers.IO) { currentTotal(current) }
            memoryMutex.withLock {
                memory = operation(memory, total)
                _state.update { it.copy(memoryText = fmt(memory)) }
            }
        }
    }

    private fun currentTotal(snapshot: Snapshot): BigDecimal {
        val parsed = CalcFile.parse(snapshot.text, snapshot.meta)
        return TapeEvaluator.evaluate(parsed.lines, snapshot.settings.decimals).grandTotal
    }

    private fun fmt(value: BigDecimal, displayDecimals: Int = decimals): String =
        TapeFormatter.formatNum(
            value,
            displayDecimals,
            false,
            Grouping.OFF,
            meta.decSep,
            meta.thouSep,
        )

    private fun lineIndexAt(text: String, offset: Int): Int = lineColumn(text, offset).first

    private fun mapCursor(selection: TextRange, oldText: String, newText: String): TextRange {
        val start = lineColumn(oldText, selection.start)
        val end = lineColumn(oldText, selection.end)
        return TextRange(offsetAt(newText, start.first, start.second), offsetAt(newText, end.first, end.second))
    }

    private fun lineColumn(text: String, offset: Int): Pair<Int, Int> {
        val safe = offset.coerceIn(0, text.length)
        var line = 0
        var lineStart = 0
        var index = 0
        while (index < safe) {
            if (text[index] == '\r') {
                if (index + 1 < safe && text[index + 1] == '\n') index++
                line++
                lineStart = index + 1
            } else if (text[index] == '\n') {
                line++
                lineStart = index + 1
            }
            index++
        }
        return line to (safe - lineStart)
    }

    private fun offsetAt(text: String, line: Int, column: Int): Int {
        var currentLine = 0
        var index = 0
        while (currentLine < line && index < text.length) {
            when (text[index]) {
                '\r' -> {
                    if (index + 1 < text.length && text[index + 1] == '\n') index++
                    currentLine++
                }
                '\n' -> currentLine++
            }
            index++
        }
        var lineEnd = text.length
        var scan = index
        while (scan < text.length) {
            if (text[scan] == '\r' || text[scan] == '\n') {
                lineEnd = scan
                break
            }
            scan++
        }
        return (index + column.coerceIn(0, (lineEnd - index).coerceAtLeast(0))).coerceIn(0, text.length)
    }

    private fun pushUndo(snapshot: Snapshot) {
        undoStack.addLast(snapshot)
        undoBytes += snapshot.textBytes
        while (undoStack.size > 50 || undoBytes > 1_000_000L) {
            val removed = undoStack.removeFirstOrNull() ?: break
            undoBytes = (undoBytes - removed.textBytes).coerceAtLeast(0L)
        }
        redoStack.clear()
        redoBytes = 0L
        updateHistoryState()
    }

    private fun updateHistoryState() {
        _state.update { it.copy(canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty()) }
    }

    private fun adoptDecimals(value: Int) {
        val safe = TapeLimits.safeDecimals(value)
        if (safe != decimals) updateSettings(_state.value.settings.copy(decimals = safe))
    }

    private fun displayNameOf(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast("/")
    } catch (_: Throwable) {
        null
    }

    private fun readBounded(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > TapeLimits.MAX_INPUT_CHARS) throw IllegalArgumentException("input exceeds supported size")
            output.write(buffer, 0, count)
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(output.toByteArray())).toString()
        if (text.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
            throw IllegalArgumentException("input contains unsupported control characters")
        }
        return text
    }

    private fun appVersion(app: Application): String = try {
        @Suppress("DEPRECATION")
        val pkg = app.packageManager.getPackageInfo(app.packageName, 0)
        "v${pkg.versionName} (${pkg.versionCode})"
    } catch (_: Throwable) {
        "v?.? (?)"
    }
}
