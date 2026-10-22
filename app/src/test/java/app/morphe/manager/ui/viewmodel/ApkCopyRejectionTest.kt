/*
 * Copyright 2026 Morphe.
 * https://github.com/Desplicableme/morphe-manager
 */

package app.morphe.manager.ui.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApkCopyRejectionTest {

    @Test
    fun `a provider that gives no stream is an unreadable file`() {
        assertEquals(ApkLoadResult.Unreadable, apkCopyRejection(null))
    }

    @Test
    fun `an empty file was read and is not an APK, not an unreadable one`() {
        assertEquals(ApkLoadResult.NotAnApk, apkCopyRejection(0L))
    }

    @Test
    fun `a file with bytes goes on to be parsed`() {
        assertNull(apkCopyRejection(1L))
        assertNull(apkCopyRejection(60_000_000L))
    }
}
