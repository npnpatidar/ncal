package com.npnpatidar.ncal.storage

import com.npnpatidar.ncal.tape.CalcMeta
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class JournalEntry(
    val noteId: String,
    val generation: Long,
    val text: String,
    val meta: CalcMeta,
)

data class PlannedEntry(val file: File, val entry: JournalEntry)

data class ReplayPlan(val apply: List<PlannedEntry>, val discard: List<File>)

class NoteJournal(private val dir: File) {

    fun stage(entry: JournalEntry, sync: Boolean): Boolean {
        if (entry.noteId.isBlank()) return false
        return try {
            dir.mkdirs()
            prune()
            val name = "entry-${System.currentTimeMillis()}-${Math.abs(JOURNAL_RANDOM.nextInt())}.journal"
            val temporary = File(dir, "$name.tmp")
            FileOutputStream(temporary).use { output ->
                output.write(encode(entry))
                output.flush()
                if (sync) output.fd.sync()
            }
            val target = File(dir, name)
            if (!temporary.renameTo(target)) {
                temporary.delete()
                return false
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun pending(): List<File> {
        val files = try {
            dir.listFiles { file -> file.isFile && file.name.endsWith(".journal") }
        } catch (_: Throwable) {
            null
        } ?: return emptyList()
        return files.sortedBy { it.name }
    }

    fun read(file: File): JournalEntry? {
        return try {
            decode(file.readBytes())
        } catch (_: Throwable) {
            null
        }
    }

    fun delete(file: File) {
        try {
            file.delete()
        } catch (_: Throwable) {
        }
    }

    fun discardUpTo(noteId: String, generation: Long) {
        for (file in pending()) {
            val entry = read(file) ?: continue
            if (entry.noteId == noteId && entry.generation <= generation) delete(file)
        }
    }

    fun discardNote(noteId: String) {
        for (file in pending()) {
            val entry = read(file) ?: continue
            if (entry.noteId == noteId) delete(file)
        }
    }

    fun clearTemps() {
        val temps = try {
            dir.listFiles { file -> file.isFile && file.name.endsWith(".tmp") }
        } catch (_: Throwable) {
            null
        } ?: return
        temps.forEach { delete(it) }
    }

    fun planReplay(
        checkGenerations: Boolean,
        isDeleted: (String) -> Boolean,
        isCurrentGeneration: (Long) -> Boolean,
    ): ReplayPlan {
        val apply = mutableListOf<PlannedEntry>()
        val discard = mutableListOf<File>()
        for (file in pending()) {
            val entry = read(file)
            if (entry == null || isDeleted(entry.noteId) ||
                (checkGenerations && !isCurrentGeneration(entry.generation))
            ) {
                discard += file
            } else {
                apply += PlannedEntry(file, entry)
            }
        }
        return ReplayPlan(apply, discard)
    }

    private fun prune(max: Int = 50) {
        val files = pending()
        if (files.size < max) return
        files.take(files.size - max + 1).forEach { delete(it) }
    }

    private fun encode(entry: JournalEntry): ByteArray {
        val uuid = entry.meta.uuid.replace("\n", "").replace("\r", "").take(128)
        val header = listOf(
            "v1",
            entry.noteId,
            entry.generation.toString(),
            entry.meta.decimals.toString(),
            entry.meta.decSep.code.toString(),
            entry.meta.thouSep.code.toString(),
            uuid,
            entry.meta.caretLine.toString(),
            entry.meta.caretOffset.toString(),
        ).joinToString("\n") + "\n"
        val headerBytes = header.toByteArray(Charsets.UTF_8)
        val textBytes = entry.text.toByteArray(Charsets.UTF_8)
        return headerBytes + textBytes.size.toString().toByteArray(Charsets.UTF_8) +
            "\n".toByteArray(Charsets.UTF_8) + textBytes
    }

    private fun decode(bytes: ByteArray): JournalEntry? {
        val text = bytes.toString(Charsets.UTF_8)
        val lines = text.split("\n")
        if (lines.size < 11 || lines[0] != "v1") return null
        val noteId = lines[1]
        val generation = lines[2].toLongOrNull() ?: return null
        val decimals = lines[3].toIntOrNull() ?: return null
        val decSep = lines[4].toIntOrNull()?.toChar() ?: return null
        val thouSep = lines[5].toIntOrNull()?.toChar() ?: return null
        val uuid = lines[6]
        val caretLine = lines[7].toIntOrNull() ?: return null
        val caretOffset = lines[8].toIntOrNull() ?: return null
        val textLength = lines[9].toLongOrNull() ?: return null
        if (noteId.isBlank() || textLength < 0) return null
        val body = lines.drop(10).joinToString("\n")
        if (body.toByteArray(Charsets.UTF_8).size.toLong() != textLength) return null
        return JournalEntry(
            noteId = noteId,
            generation = generation,
            text = body,
            meta = CalcMeta(
                decimals = decimals,
                decSep = decSep,
                thouSep = thouSep,
                uuid = uuid,
                caretLine = caretLine,
                caretOffset = caretOffset,
            ),
        )
    }

    companion object {
        private val JOURNAL_RANDOM = java.util.Random()
    }
}
