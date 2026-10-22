/*
 * Copyright 2026 Morphe.
 * https://github.com/Desplicableme/morphe-manager
 */

package app.morphe.manager.domain.repository

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageStatsBytesTest {

    @Test
    fun `nothing and a few leftover bytes count as nothing to remove`() {
        assertFalse(0L >= StorageStats.MIN_REMOVABLE_BYTES)
        assertFalse(36L >= StorageStats.MIN_REMOVABLE_BYTES)
        assertFalse((StorageStats.MIN_REMOVABLE_BYTES - 1) >= StorageStats.MIN_REMOVABLE_BYTES)
    }

    @Test
    fun `a kilobyte or more is worth a clean-up`() {
        assertTrue(StorageStats.MIN_REMOVABLE_BYTES >= StorageStats.MIN_REMOVABLE_BYTES)
        assertTrue(1_200L >= StorageStats.MIN_REMOVABLE_BYTES)
        assertTrue(250_000_000L >= StorageStats.MIN_REMOVABLE_BYTES)
    }
}
