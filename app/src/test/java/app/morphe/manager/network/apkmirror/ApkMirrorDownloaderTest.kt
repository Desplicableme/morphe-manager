/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.network.apkmirror

import kotlin.test.*

class ApkMirrorDownloaderTest {

    private val downloader = ApkMirrorDownloader()

    @Test
    fun `isVariantDownloadPage correctly classifies urls`() {
        assertTrue(downloader.isVariantDownloadPage("https://www.apkmirror.com/apk/google-inc/youtube/youtube-20-14-35-release/youtube-20-14-35-android-apk-download/"))
        assertTrue(downloader.isVariantDownloadPage("https://www.apkmirror.com/apk/google-inc/youtube/youtube-20-14-35-release/youtube-20-14-35-android-apk-download"))
        assertFalse(downloader.isVariantDownloadPage("https://www.apkmirror.com/apk/google-inc/youtube/youtube-20-14-35-release/"))
        assertFalse(downloader.isVariantDownloadPage("https://www.apkmirror.com/apk/google-inc/youtube/"))
    }

    @Test
    fun `isReleasePage correctly classifies urls`() {
        assertTrue(downloader.isReleasePage("https://www.apkmirror.com/apk/google-inc/youtube/youtube-20-14-35-release/"))
        assertTrue(downloader.isReleasePage("https://www.apkmirror.com/apk/google-inc/youtube/youtube-20-14-35-release"))
        assertFalse(downloader.isReleasePage("https://www.apkmirror.com/apk/google-inc/youtube/youtube-20-14-35-release/youtube-20-14-35-android-apk-download/"))
        assertFalse(downloader.isReleasePage("https://www.apkmirror.com/apk/google-inc/youtube/"))
    }

    @Test
    fun `detectExtension extracts extension from content disposition or url`() {
        assertEquals("apk", downloader.detectExtension("""attachment; filename="YouTube_20.14.35.apk"""", "https://example.com/stream"))
        assertEquals("apkm", downloader.detectExtension("""attachment; filename="YouTube_20.14.35.apkm"""", "https://example.com/stream"))
        assertEquals("xapk", downloader.detectExtension("""attachment; filename=base.xapk""", "https://example.com/stream"))
        assertEquals("apkm", downloader.detectExtension(null, "https://storage.cloudflare.com/bucket/file.apkm?token=123"))
        assertEquals("apk", downloader.detectExtension(null, "https://storage.cloudflare.com/bucket/file.apk?token=123"))
        assertEquals("apk", downloader.detectExtension(null, "https://storage.cloudflare.com/bucket/unknown"))
    }

    @Test
    fun `unescapeHtml cleans html entities`() {
        assertEquals("foo & bar", downloader.unescapeHtml("foo &amp; bar"))
        assertEquals("foo & bar", downloader.unescapeHtml("foo &#038; bar"))
        assertEquals("""Hello "World" 'Test'""", downloader.unescapeHtml("""Hello &quot;World&quot; &#39;Test&#39;"""))
    }

    @Test
    fun `parseReleaseLinks extracts releases from search html`() {
        val sampleSearchHtml = """
            <div class="listWidget">
                <div class="appRow">
                    <h5 class="appRowTitle">
                        <a class="fontBlack" href="/apk/google-inc/youtube/youtube-20-14-35-release/">YouTube 20.14.35</a>
                    </h5>
                </div>
                <div class="appRow">
                    <h5 class="appRowTitle">
                        <a class="fontBlack" href="/apk/google-inc/youtube/youtube-20-14-34-release/">YouTube 20.14.34 beta</a>
                    </h5>
                </div>
            </div>
        """.trimIndent()

        val releases = downloader.parseReleaseLinks(sampleSearchHtml)
        assertEquals(2, releases.size)
        assertEquals("https://www.apkmirror.com/apk/google-inc/youtube/youtube-20-14-35-release/", releases[0].first)
        assertEquals("YouTube 20.14.35", releases[0].second)
        assertEquals("https://www.apkmirror.com/apk/google-inc/youtube/youtube-20-14-34-release/", releases[1].first)
        assertEquals("YouTube 20.14.34 beta", releases[1].second)
    }

    @Test
    fun `parseVariants extracts variants and attributes`() {
        val sampleReleaseHtml = """
            <div class="variants-table">
                <div class="table-row">
                    <div class="table-cell">
                        <a class="accent_color" href="/apk/google-inc/youtube/youtube-20-14-35-release/youtube-20-14-35-2-android-apk-download/">20.14.35</a>
                    </div>
                    <div class="table-cell">arm64-v8a</div>
                    <div class="table-cell">Android 8.0+</div>
                    <div class="table-cell">nodpi</div>
                </div>
                </div>
                <div class="table-row">
                    <div class="table-cell">
                        <span class="apkm-badge">BUNDLE</span>
                        <a class="accent_color" href="/apk/google-inc/youtube/youtube-20-14-35-release/youtube-20-14-35-3-android-apk-download/">20.14.35</a>
                    </div>
                    <div class="table-cell">universal</div>
                    <div class="table-cell">Android 8.0+</div>
                    <div class="table-cell">nodpi</div>
                </div>
                </div>
            </div>
        """.trimIndent()

        val variants = downloader.parseVariants(sampleReleaseHtml)
        assertEquals(2, variants.size)

        val apkVariant = variants[0]
        assertFalse(apkVariant.isBundle)
        assertEquals("arm64-v8a", apkVariant.arch)
        assertEquals("nodpi", apkVariant.dpi)
        assertEquals("https://www.apkmirror.com/apk/google-inc/youtube/youtube-20-14-35-release/youtube-20-14-35-2-android-apk-download/", apkVariant.url)

        val bundleVariant = variants[1]
        assertTrue(bundleVariant.isBundle)
        assertEquals("universal", bundleVariant.arch)
        assertEquals("nodpi", bundleVariant.dpi)
    }

    @Test
    fun `selectBestVariant prefers apk or bundle depending on isBundleRequired`() {
        val apkVariant = ApkMirrorVariant(
            url = "https://www.apkmirror.com/variant/apk",
            version = "20.14.35",
            isBundle = false,
            arch = "arm64-v8a",
            minAndroidVersion = "Android 8.0+",
            dpi = "nodpi"
        )
        val bundleVariant = ApkMirrorVariant(
            url = "https://www.apkmirror.com/variant/bundle",
            version = "20.14.35",
            isBundle = true,
            arch = "arm64-v8a",
            minAndroidVersion = "Android 8.0+",
            dpi = "nodpi"
        )
        val variants = listOf(bundleVariant, apkVariant)

        // When bundle is strictly required, selects bundle
        val selectedForBundle = downloader.selectBestVariant(variants, isBundleRequired = true)
        assertTrue(selectedForBundle.isBundle)
        assertEquals("https://www.apkmirror.com/variant/bundle", selectedForBundle.url)

        // When bundle is not required (standard APK preferred), selects standard APK
        val selectedForApk = downloader.selectBestVariant(variants, isBundleRequired = false)
        assertFalse(selectedForApk.isBundle)
        assertEquals("https://www.apkmirror.com/variant/apk", selectedForApk.url)

        // When isBundleRequired is null, standard APK receives a bonus score
        val selectedDefault = downloader.selectBestVariant(variants, isBundleRequired = null)
        assertFalse(selectedDefault.isBundle)
    }

    @Test
    fun `parseDownloadKeyPageUrlFromHtml extracts key page url`() {
        val html1 = """
            <div>
                <a class="accent_bg btn post-page-btn downloadButton" href="/apk/google-inc/youtube/youtube-20-14-35-release/youtube-20-14-35-android-apk-download/download/?key=abcdef123456">Download APK</a>
            </div>
        """.trimIndent()
        val url1 = downloader.parseDownloadKeyPageUrlFromHtml(html1)
        assertEquals("https://www.apkmirror.com/apk/google-inc/youtube/youtube-20-14-35-release/youtube-20-14-35-android-apk-download/download/?key=abcdef123456", url1)

        val html2 = """
            <a href="https://www.apkmirror.com/download/?key=testkey&amp;id=999">Download</a>
        """.trimIndent()
        val url2 = downloader.parseDownloadKeyPageUrlFromHtml(html2)
        assertEquals("https://www.apkmirror.com/download/?key=testkey&id=999", url2)

        val htmlSingleQuote = """
            <a class='downloadButton' href='/apk/google-inc/youtube/download/?key=singlequote'>Download</a>
        """.trimIndent()
        val urlSingle = downloader.parseDownloadKeyPageUrlFromHtml(htmlSingleQuote)
        assertEquals("https://www.apkmirror.com/apk/google-inc/youtube/download/?key=singlequote", urlSingle)
    }

    @Test
    fun `parseDownloadPhpUrlFromHtml extracts direct download endpoint`() {
        val html1 = """
            <p class="notes">If the download does not start, click <a id="download-link" href="/wp-content/themes/APKMirror/download.php?id=12345&amp;key=67890">here</a>.</p>
        """.trimIndent()
        val url1 = downloader.parseDownloadPhpUrlFromHtml(html1)
        assertEquals("https://www.apkmirror.com/wp-content/themes/APKMirror/download.php?id=12345&key=67890", url1)

        val html2 = """
            <a href="https://www.apkmirror.com/wp-content/themes/APKMirror/download.php?id=999">here</a>
        """.trimIndent()
        val url2 = downloader.parseDownloadPhpUrlFromHtml(html2)
        assertEquals("https://www.apkmirror.com/wp-content/themes/APKMirror/download.php?id=999", url2)

        val htmlReverse = """
            <a href="/wp-content/themes/APKMirror/download.php?id=777" id="download-link">Click</a>
        """.trimIndent()
        val url3 = downloader.parseDownloadPhpUrlFromHtml(htmlReverse)
        assertEquals("https://www.apkmirror.com/wp-content/themes/APKMirror/download.php?id=777", url3)

        val htmlPhpSingle = """
            <a href='/wp-content/themes/APKMirror/download.php?id=888' id='download-link'>Click</a>
        """.trimIndent()
        val url4 = downloader.parseDownloadPhpUrlFromHtml(htmlPhpSingle)
        assertEquals("https://www.apkmirror.com/wp-content/themes/APKMirror/download.php?id=888", url4)
    }

    @Test
    fun `isDirectDownloadUrl correctly identifies stream links`() {
        assertTrue(downloader.isDirectDownloadUrl("https://www.apkmirror.com/wp-content/themes/APKMirror/download.php?id=123"))
        assertTrue(downloader.isDirectDownloadUrl("https://storage.r2.cloudflarestorage.com/downloadprod/file.apk?token=xyz"))
        assertTrue(downloader.isDirectDownloadUrl("https://example.com/app.apkm?token=xyz"))
        assertFalse(downloader.isDirectDownloadUrl("https://www.apkmirror.com/apk/google-inc/youtube/youtube-20-14-35-release/"))
    }

    @Test
    fun `isDownloadKeyPage correctly identifies key pages`() {
        assertTrue(downloader.isDownloadKeyPage("https://www.apkmirror.com/apk/google-inc/youtube/youtube-20-14-35-release/youtube-20-14-35-android-apk-download/download/?key=abcdef123"))
        assertTrue(downloader.isDownloadKeyPage("https://www.apkmirror.com/download/?key=testkey&id=999"))
        assertFalse(downloader.isDownloadKeyPage("https://www.apkmirror.com/apk/google-inc/youtube/youtube-20-14-35-release/"))
        assertFalse(downloader.isDownloadKeyPage("https://www.apkmirror.com/apk/google-inc/youtube/youtube-20-14-35-release/youtube-20-14-35-android-apk-download/"))
    }

    @Test
    fun `isSearchOrCategoryPage correctly identifies search and category listings`() {
        assertTrue(downloader.isSearchOrCategoryPage("https://www.apkmirror.com/?post_type=app_release&searchtype=apk&s=youtube"))
        assertTrue(downloader.isSearchOrCategoryPage("https://www.apkmirror.com/uploads/?appcategory=youtube"))
        assertTrue(downloader.isSearchOrCategoryPage("https://www.apkmirror.com/apk/google-inc/youtube/"))
        assertFalse(downloader.isSearchOrCategoryPage("https://www.apkmirror.com/apk/google-inc/youtube/youtube-20-14-35-release/"))
    }

    @Test
    fun `parseReleaseLinks ignores sidebar entries`() {
        val htmlWithSidebar = """
            <div class="listWidget">
                <div class="appRow">
                    <h5 class="appRowTitle">
                        <a class="fontBlack" href="/apk/google-inc/youtube/youtube-20-14-35-release/">YouTube 20.14.35</a>
                    </h5>
                </div>
            </div>
            <div id="sidebar">
                <div class="appRow">
                    <h5 class="appRowTitle">
                        <a class="fontBlack" href="/apk/bytedance-pte-ltd/capcut/capcut-19-0-release/">CapCut 19.0</a>
                    </h5>
                </div>
            </div>
        """.trimIndent()

        val releases = downloader.parseReleaseLinks(htmlWithSidebar)
        assertEquals(1, releases.size)
        assertEquals("YouTube 20.14.35", releases[0].second)
    }

    @Test
    fun `companion isDirectDownloadUrl behaves identically to instance method`() {
        val testUrl = "https://www.apkmirror.com/wp-content/themes/APKMirror/download.php?id=123"
        assertTrue(ApkMirrorDownloader.isDirectDownloadUrl(testUrl))
        assertEquals(downloader.isDirectDownloadUrl(testUrl), ApkMirrorDownloader.isDirectDownloadUrl(testUrl))
    }

    @Test
    fun `CloudflareVerificationException retains verificationUrl and metadata`() {
        val exception = CloudflareVerificationException(
            verificationUrl = "https://www.apkmirror.com/challenge",
            packageName = "com.google.android.youtube",
            versionName = "20.14.35"
        )
        assertEquals("https://www.apkmirror.com/challenge", exception.verificationUrl)
        assertEquals("com.google.android.youtube", exception.packageName)
        assertEquals("20.14.35", exception.versionName)
        assertTrue(exception.message!!.contains("Cloudflare"))
    }
}

