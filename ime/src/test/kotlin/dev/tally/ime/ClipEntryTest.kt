package dev.tally.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipEntryTest {

    @Test
    fun defaultFieldValues() {
        val entry = ClipEntry(
            id          = "1",
            text        = "hello",
            timestampMs = 1000L,
        )
        assertFalse(entry.isPinned)
        assertFalse(entry.isSensitive)
        assertEquals(EntityType.NONE, entry.entityType)
    }

    @Test
    fun copyWithPinned() {
        val original = ClipEntry(id = "2", text = "world", timestampMs = 2000L)
        val pinned   = original.copy(isPinned = true)
        assertTrue(pinned.isPinned)
        assertEquals(original.text, pinned.text)
        assertEquals(original.id, pinned.id)
    }

    @Test
    fun entityTypePreserved() {
        val entry = ClipEntry(
            id          = "3",
            text        = "https://example.com",
            timestampMs = 3000L,
            entityType  = EntityType.URL,
        )
        assertEquals(EntityType.URL, entry.entityType)
    }

    @Test
    fun sensitiveFlag() {
        val entry = ClipEntry(
            id          = "4",
            text        = "secret",
            timestampMs = 4000L,
            isSensitive = true,
        )
        assertTrue(entry.isSensitive)
    }

    @Test
    fun equalityBasedOnAllFields() {
        val a = ClipEntry(id = "5", text = "abc", timestampMs = 5000L, isPinned = true)
        val b = ClipEntry(id = "5", text = "abc", timestampMs = 5000L, isPinned = true)
        assertEquals(a, b)
    }

    @Test
    fun entityTypeEnum_allValues() {
        val types = EntityType.values()
        assertTrue(EntityType.NONE in types)
        assertTrue(EntityType.URL in types)
        assertTrue(EntityType.EMAIL in types)
        assertTrue(EntityType.PHONE in types)
        assertTrue(EntityType.ADDRESS in types)
    }
}
