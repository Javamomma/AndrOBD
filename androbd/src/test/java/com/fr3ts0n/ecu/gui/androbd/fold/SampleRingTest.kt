package com.fr3ts0n.ecu.gui.androbd.fold

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SampleRingTest {

    @Test
    fun snapshotKeepsOnePointBeforeTheWindow() {
        val ring = SampleRing(16)
        for (i in 1..5) ring.add(i * 1000L, i.toFloat())
        val s = ring.snapshot(from = 3500L)
        // 3000 is kept so the line enters from the left edge, then 4000 and 5000
        assertArrayEquals(longArrayOf(3000L, 4000L, 5000L), s.t)
        assertArrayEquals(floatArrayOf(3f, 4f, 5f), s.v, 0f)
    }

    @Test
    fun snapshotFromZeroReturnsEverything() {
        val ring = SampleRing(16)
        for (i in 1..3) ring.add(i * 10L, i.toFloat())
        assertEquals(3, ring.snapshot(0L).size)
    }

    @Test
    fun wrapsAroundAndKeepsNewestInOrder() {
        val ring = SampleRing(4)
        for (i in 1..10) ring.add(i.toLong(), i.toFloat())
        val s = ring.snapshot(0L)
        assertArrayEquals(longArrayOf(7L, 8L, 9L, 10L), s.t)
        assertEquals(10f, ring.lastValue()!!, 0f)
        assertEquals(10L, ring.lastTime())
    }

    @Test
    fun emptyRing() {
        val ring = SampleRing(4)
        assertEquals(0, ring.snapshot(0L).size)
        assertNull(ring.lastValue())
        assertEquals(0L, ring.lastTime())
    }

    @Test
    fun statsUseOnlyTheWindow() {
        val s = Series(longArrayOf(1, 2, 3, 4), floatArrayOf(100f, 2f, 4f, 6f))
        val st = s.stats(from = 2, to = 4)!!
        assertEquals(2f, st.min, 0f)
        assertEquals(4f, st.avg, 0f)
        assertEquals(6f, st.max, 0f)
    }

    @Test
    fun statsFallBackToHeldValue() {
        // nothing new inside the window: the last value before it still holds
        val s = Series(longArrayOf(1, 2), floatArrayOf(5f, 7f))
        val st = s.stats(from = 10, to = 20)!!
        assertEquals(7f, st.min, 0f)
        assertEquals(7f, st.max, 0f)
    }

    @Test
    fun groupsLongPlainNumbers() {
        val saved = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.US)
        try {
            assertEquals("13,750", groupDigits("13750"))
            assertEquals("1,234.5", groupDigits("1234.5"))
            assertEquals("987", groupDigits("987"))
            assertEquals("Off", groupDigits("Off"))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun durations() {
        assertEquals("0:05", formatDuration(5_000))
        assertEquals("12:30", formatDuration(750_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
        assertEquals("13 min", formatDurationWords(13 * 60_000L + 5_000))
        assertEquals("1 h 5 min", formatDurationWords(65 * 60_000L))
    }
}
