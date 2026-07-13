package com.don194.photoswipe.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaStoreRepositoryTest {
    @Test
    fun `photo date prefers date taken`() {
        assertEquals(3_000L, resolvePhotoDateMillis(3_000L, 2L, 1L))
    }

    @Test
    fun `photo date falls back to modified then added time`() {
        assertEquals(2_000L, resolvePhotoDateMillis(0L, 2L, 1L))
        assertEquals(1_000L, resolvePhotoDateMillis(0L, 0L, 1L))
        assertEquals(0L, resolvePhotoDateMillis(0L, 0L, 0L))
    }
}
