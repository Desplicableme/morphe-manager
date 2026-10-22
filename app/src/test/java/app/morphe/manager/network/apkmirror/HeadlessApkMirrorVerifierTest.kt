/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.network.apkmirror

import kotlin.test.*

class HeadlessApkMirrorVerifierTest {

    @Test
    fun `isChallenge correctly identifies Cloudflare challenge titles and urls`() {
        assertTrue(HeadlessApkMirrorVerifier.isChallenge("Just a moment...", "https://www.apkmirror.com/apk/google-inc/youtube/"))
        assertTrue(HeadlessApkMirrorVerifier.isChallenge("Attention Required! | Cloudflare", "https://www.apkmirror.com/apk/"))
        assertTrue(HeadlessApkMirrorVerifier.isChallenge("Cloudflare verification", "https://www.apkmirror.com/"))
        assertTrue(HeadlessApkMirrorVerifier.isChallenge("Normal Title", "https://challenges.cloudflare.com/turnstile/v0/api.js"))
        assertTrue(HeadlessApkMirrorVerifier.isChallenge("Normal Title", "https://www.apkmirror.com/cf-mitigated/challenge"))

        assertFalse(HeadlessApkMirrorVerifier.isChallenge("YouTube 19.16.39 APK Download", "https://www.apkmirror.com/apk/google-inc/youtube/youtube-19-16-39-release/"))
        assertFalse(HeadlessApkMirrorVerifier.isChallenge("Download YouTube APK", "https://www.apkmirror.com/wp-content/themes/APKMirror/download.php?id=123"))
    }

    @Test
    fun `isContentPage requires apkmirror domain and absence of challenge`() {
        assertTrue(
            HeadlessApkMirrorVerifier.isContentPage(
                title = "YouTube 19.16.39 APK Download by Google LLC - APKMirror",
                url = "https://www.apkmirror.com/apk/google-inc/youtube/youtube-19-16-39-release/"
            )
        )

        assertFalse(
            HeadlessApkMirrorVerifier.isContentPage(
                title = "Just a moment...",
                url = "https://www.apkmirror.com/apk/google-inc/youtube/youtube-19-16-39-release/"
            )
        )

        assertFalse(
            HeadlessApkMirrorVerifier.isContentPage(
                title = "Attention Required! | Cloudflare",
                url = "https://www.apkmirror.com/apk/google-inc/youtube/"
            )
        )

        assertFalse(
            HeadlessApkMirrorVerifier.isContentPage(
                title = "External Site",
                url = "https://example.com/download"
            )
        )
    }

    @Test
    fun `auto click javascript targets download elements`() {
        val js = HeadlessApkMirrorVerifier.AUTO_CLICK_JS
        assertTrue(js.contains("a.downloadButton"))
        assertTrue(js.contains("a#download-link"))
        assertTrue(js.contains("""a[href*="download.php"]"""))
        assertTrue(js.contains("btn.click()"))
    }

    @Test
    fun `verification results instantiate correctly`() {
        val successWithUrl = HeadlessVerificationResult.Success("https://download.apkmirror.com/file.apk")
        assertEquals("https://download.apkmirror.com/file.apk", successWithUrl.directDownloadUrl)

        val successClearanceOnly = HeadlessVerificationResult.Success(null)
        assertNull(successClearanceOnly.directDownloadUrl)

        val requiresInteraction = HeadlessVerificationResult.RequiresInteraction
        assertNotNull(requiresInteraction)

        val exception = IllegalStateException("Test error")
        val failed = HeadlessVerificationResult.Failed(exception)
        assertEquals(exception, failed.cause)
    }
}
