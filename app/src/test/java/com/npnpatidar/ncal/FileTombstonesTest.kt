package com.npnpatidar.ncal

import com.npnpatidar.ncal.storage.FileTombstones
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileTombstonesTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun markAndCheckRoundTrip() {
        val stones = FileTombstones(folder.root)
        assertFalse(stones.isMarked("note-1"))
        stones.mark("note-1")
        assertTrue(stones.isMarked("note-1"))
        assertFalse(stones.isMarked("note-2"))
    }

    @Test
    fun marksAreVisibleAcrossInstances() {
        FileTombstones(folder.root).mark("shared-note")
        assertTrue(FileTombstones(folder.root).isMarked("shared-note"))
    }

    @Test
    fun blankIdsAreIgnored() {
        val stones = FileTombstones(folder.root)
        stones.mark("")
        assertFalse(stones.isMarked(""))
        assertEquals(0, folder.root.listFiles()?.size)
    }

    @Test
    fun unsafeIdsStayInsideTheDirectory() {
        val stones = FileTombstones(folder.root)
        stones.mark("../../evil")
        assertTrue(stones.isMarked("../../evil"))
        assertEquals(1, folder.root.listFiles()?.size)
        assertTrue(File(folder.root, ".tombstone-______evil").exists())
    }

    @Test
    fun marksAreNeverEvicted() {
        val stones = FileTombstones(folder.root)
        for (index in 0 until 205) {
            stones.mark("note-$index")
        }
        val remaining = folder.root.listFiles { file -> file.name.startsWith(".tombstone-") }
        assertEquals(205, remaining?.size)
        assertTrue(stones.isMarked("note-0"))
        assertTrue(stones.isMarked("note-204"))
    }
}
