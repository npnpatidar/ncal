package com.npnpatidar.ncal

import com.npnpatidar.ncal.storage.JournalEntry
import com.npnpatidar.ncal.storage.NoteJournal
import com.npnpatidar.ncal.tape.CalcMeta
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoteJournalTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun entry(
        id: String = "note-1",
        generation: Long = 1L,
        text: String = " + 1\n",
    ) = JournalEntry(id, generation, text, CalcMeta(decimals = 2))

    @Test
    fun stageAndReadRoundTrip() {
        val journal = NoteJournal(folder.root)
        assertTrue(journal.stage(entry(), sync = true))
        val pending = journal.pending()
        assertEquals(1, pending.size)
        val read = journal.read(pending.single())
        assertEquals("note-1", read?.noteId)
        assertEquals(1L, read?.generation)
        assertEquals(" + 1\n", read?.text)
        assertEquals(2, read?.meta?.decimals)
    }

    @Test
    fun pendingIsChronological() {
        val journal = NoteJournal(folder.root)
        journal.stage(entry("a", 1L), sync = false)
        journal.stage(entry("b", 2L), sync = false)
        val ids = journal.pending().map { journal.read(it)?.noteId }
        assertEquals(listOf("a", "b"), ids)
    }

    @Test
    fun malformedEntriesAreUnreadable() {
        val journal = NoteJournal(folder.root)
        File(folder.root, "entry-broken.journal").writeText("v1\nonly-two-lines\n")
        assertNull(journal.read(File(folder.root, "entry-broken.journal")))
        val truncated = File(folder.root, "entry-short.journal")
        truncated.writeText("v1\nnote-1\n1\n2\n46\n44\nuuid\n0\n0\n999\nshort")
        assertNull(journal.read(truncated))
    }

    @Test
    fun discardUpToKeepsNewerEntries() {
        val journal = NoteJournal(folder.root)
        journal.stage(entry("a", 1L), sync = false)
        journal.stage(entry("a", 2L), sync = false)
        journal.stage(entry("b", 1L), sync = false)
        journal.discardUpTo("a", 1L)
        val remaining = journal.pending().mapNotNull { journal.read(it) }
        assertEquals(2, remaining.size)
        assertTrue(remaining.any { it.noteId == "a" && it.generation == 2L })
        assertTrue(remaining.any { it.noteId == "b" })
    }

    @Test
    fun discardNoteRemovesAllGenerations() {
        val journal = NoteJournal(folder.root)
        journal.stage(entry("a", 1L), sync = false)
        journal.stage(entry("a", 2L), sync = false)
        journal.discardNote("a")
        assertTrue(journal.pending().isEmpty())
    }

    @Test
    fun planReplayAppliesInOrder() {
        val journal = NoteJournal(folder.root)
        journal.stage(entry("a", 1L), sync = false)
        Thread.sleep(5)
        journal.stage(entry("b", 2L), sync = false)
        val plan = journal.planReplay(
            checkGenerations = false,
            isDeleted = { false },
            isCurrentGeneration = { true },
        )
        assertEquals(listOf("a", "b"), plan.apply.map { it.entry.noteId })
        assertTrue(plan.discard.isEmpty())
    }

    @Test
    fun planReplayDiscardsDeletedAndStaleEntries() {
        val journal = NoteJournal(folder.root)
        journal.stage(entry("gone", 1L), sync = false)
        journal.stage(entry("stale", 1L), sync = false)
        journal.stage(entry("fresh", 3L), sync = false)
        val plan = journal.planReplay(
            checkGenerations = true,
            isDeleted = { it == "gone" },
            isCurrentGeneration = { it == 3L },
        )
        assertEquals(listOf("fresh"), plan.apply.map { it.entry.noteId })
        assertEquals(2, plan.discard.size)
    }

    @Test
    fun planReplayDiscardsMalformedEntries() {
        val journal = NoteJournal(folder.root)
        File(folder.root, "entry-broken.journal").writeText("not a journal entry")
        journal.stage(entry("a", 1L), sync = false)
        val plan = journal.planReplay(
            checkGenerations = false,
            isDeleted = { false },
            isCurrentGeneration = { true },
        )
        assertEquals(listOf("a"), plan.apply.map { it.entry.noteId })
        assertEquals(1, plan.discard.size)
    }

    @Test
    fun restartRecoveryAppliesStagedEntries() {
        val firstBoot = NoteJournal(folder.root)
        firstBoot.stage(entry("a", 1L, " + 1\n"), sync = true)
        Thread.sleep(5)
        firstBoot.stage(entry("b", 2L, " + 2\n"), sync = true)
        val secondBoot = NoteJournal(folder.root)
        val plan = secondBoot.planReplay(
            checkGenerations = false,
            isDeleted = { false },
            isCurrentGeneration = { false },
        )
        assertEquals(listOf("a", "b"), plan.apply.map { it.entry.noteId })
        assertEquals(" + 1\n", plan.apply[0].entry.text)
        assertEquals(" + 2\n", plan.apply[1].entry.text)
        assertTrue(plan.discard.isEmpty())
    }

    @Test
    fun blankIdsAreRejected() {
        val journal = NoteJournal(folder.root)
        assertFalse(journal.stage(entry("", 1L), sync = true))
        assertTrue(journal.pending().isEmpty())
    }

    @Test
    fun entryCapKeepsNewest() {
        val journal = NoteJournal(folder.root)
        for (index in 0 until 55) {
            journal.stage(entry("note-$index", index.toLong()), sync = false)
            Thread.sleep(5)
        }
        val remaining = journal.pending()
        assertTrue(remaining.size <= 50)
        val ids = remaining.mapNotNull { journal.read(it)?.noteId }
        assertTrue(ids.contains("note-54"))
    }
}
