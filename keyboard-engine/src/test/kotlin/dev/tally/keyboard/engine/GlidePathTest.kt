package dev.tally.keyboard.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GlidePathTest {

    @Test
    fun `EMPTY path has no points`() {
        assertTrue(GlidePath.EMPTY.points.isEmpty())
    }

    @Test
    fun `of factory produces paired points`() {
        val path = GlidePath.of(0f, 0f, 10f, 20f, 30f, 40f)
        assertEquals(3, path.points.size)
        assertEquals(GlidePoint(0f, 0f), path.points[0])
        assertEquals(GlidePoint(10f, 20f), path.points[1])
        assertEquals(GlidePoint(30f, 40f), path.points[2])
    }

    @Test
    fun `GlidePoint preserves coordinates`() {
        val pt = GlidePoint(1.5f, 2.5f, timestamp = 123L)
        assertEquals(1.5f, pt.x)
        assertEquals(2.5f, pt.y)
        assertEquals(123L, pt.timestamp)
    }
}
