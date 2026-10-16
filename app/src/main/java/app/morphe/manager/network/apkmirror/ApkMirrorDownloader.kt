/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.network.apkmirror

import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import app.morphe.manager.data.platform.Filesystem
import app.morphe.manager.network.service.HttpService
import app.morphe.manager.util.tag
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.regex.Pattern
import kotlin.time.Duration.Companion.milliseconds

/**
 * Thrown when APKMirror triggers Cloudflare Turnstile or bot mitigation challenge.
 */
class CloudflareVerificationException(
    val verificationUrl: String,
    val packageName: String? = null,
    val versionName: String? = null,
    message: String = "APKMirror bot verification (Cloudflare) is active."
) : IOException(message)

/**
 * Represents a variant found on an APKMirror release page.
 */
data class ApkMirrorVariant(
    val url: String,
    val version: String,
    val isBundle: Boolean,
    val arch: String,
    val minAndroidVersion: String,
    val dpi: String
)

/**
 * Progress updates for in-built APKMirror download.
 */
sealed interface ApkMirrorDownloadProgress {
    data class Resolving(val message: String) : ApkMirrorDownloadProgress
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long?,
        val progress: Float?,
        val speedBytesPerSec: Long
    ) : ApkMirrorDownloadProgress
    data class Completed(val file: File, val isBundle: Boolean) : ApkMirrorDownloadProgress
}

/**
 * In-built downloader that resolves and streams APKs and split APK bundles (APKM/XAPK)
 * directly from APKMirror without requiring the user to navigate the site manually.
 *
 * Designed with multi-hop navigation, rate-limit defense (429 exponential backoff),
 * Cloudflare detection, known-category resolution, and robust table parsing.
 */
class ApkMirrorDownloader(
    private val httpService: HttpService? = null,
    private val filesystem: Filesystem? = null
) {
    private val client: HttpClient get() = httpService?.http ?: error("HttpService is required for network operations")

    /**
     * Resolves the final download link and downloads the APK or APK bundle to a temporary file.
     *
     * @param initialUrl An existing APKMirror link (download page, release page, or search URL) if known.
     * @param packageName App package identifier (e.g. "com.google.android.youtube").
     * @param versionName Specific version requested, or null for latest compatible release.
     * @param isBundleRequired Whether the patch bundle strictly requires a split archive (APKM/XAPK).
     * @param onProgress Callback receiving progress updates.
     * @return The downloaded [File] ready for parsing or patching.
     */
    suspend fun download(
        initialUrl: String? = null,
        packageName: String,
        versionName: String? = null,
        isBundleRequired: Boolean? = null,
        onProgress: suspend (ApkMirrorDownloadProgress) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val trimmed = initialUrl?.trim()

        // Fast-path 1: initialUrl is already a direct download binary or download.php URL
        if (!trimmed.isNullOrEmpty() && isDirectDownloadUrl(trimmed)) {
            Log.i(tag, "ApkMirrorDownloader: URL is already direct download binary: $trimmed")
            onProgress(ApkMirrorDownloadProgress.Resolving("Connecting directly to download stream…"))
            return@withContext downloadBinary(
                downloadUrl = trimmed,
                referrer = BASE_URL,
                packageName = packageName,
                versionName = versionName ?: "latest",
                onProgress = onProgress
            )
        }

        // Fast-path 2: initialUrl is already a download key page (/download/?key=...)
        val keyPageUrl: String = if (!trimmed.isNullOrEmpty() && trimmed.contains("apkmirror.com") && isDownloadKeyPage(trimmed)) {
            Log.i(tag, "ApkMirrorDownloader: URL is already download key page: $trimmed")
            trimmed
        } else {
            // Step 1: Find variant download page
            onProgress(ApkMirrorDownloadProgress.Resolving("Connecting to APKMirror…"))
            val variantDownloadPageUrl = resolveVariantDownloadPageUrl(
                initialUrl = trimmed,
                packageName = packageName,
                versionName = versionName,
                isBundleRequired = isBundleRequired,
                onProgress = onProgress
            )
            Log.i(tag, "ApkMirrorDownloader: Resolved variant page: $variantDownloadPageUrl")
            delay(INTER_HOP_DELAY_MS.milliseconds)

            // Step 2: Extract intermediate download key page
            if (isDownloadKeyPage(variantDownloadPageUrl)) {
                variantDownloadPageUrl
            } else {
                onProgress(ApkMirrorDownloadProgress.Resolving("Locating download key…"))
                val extractedKeyPage = extractDownloadKeyPageUrl(variantDownloadPageUrl)
                Log.i(tag, "ApkMirrorDownloader: Resolved key page: $extractedKeyPage")
                delay(INTER_HOP_DELAY_MS.milliseconds)
                extractedKeyPage
            }
        }

        // Step 3: Extract final download.php endpoint
        onProgress(ApkMirrorDownloadProgress.Resolving("Generating download link…"))
        val downloadPhpUrl = extractDownloadPhpUrl(keyPageUrl)
        Log.i(tag, "ApkMirrorDownloader: Resolved download.php URL: $downloadPhpUrl")
        delay(INTER_HOP_DELAY_MS.milliseconds)

        // Step 4: Stream and download the binary file
        onProgress(ApkMirrorDownloadProgress.Resolving("Starting download…"))
        downloadBinary(
            downloadUrl = downloadPhpUrl,
            referrer = keyPageUrl,
            packageName = packageName,
            versionName = versionName ?: "latest",
            onProgress = onProgress
        )
    }

    /**
     * Directly downloads an APK or bundle from an intercepted direct URL or download.php URL.
     */
    suspend fun downloadFromDirectUrl(
        downloadUrl: String,
        packageName: String,
        versionName: String? = null,
        referrer: String = BASE_URL,
        onProgress: suspend (ApkMirrorDownloadProgress) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        onProgress(ApkMirrorDownloadProgress.Resolving("Connecting directly to download stream…"))
        downloadBinary(
            downloadUrl = downloadUrl,
            referrer = referrer,
            packageName = packageName,
            versionName = versionName ?: "latest",
            onProgress = onProgress
        )
    }

    /**
     * Resolves a variant download page URL from [initialUrl] or by querying APKMirror releases.
     */
    private suspend fun resolveVariantDownloadPageUrl(
        initialUrl: String?,
        packageName: String,
        versionName: String?,
        isBundleRequired: Boolean?,
        onProgress: suspend (ApkMirrorDownloadProgress) -> Unit
    ): String {
        // Case A: initialUrl is already a variant download page
        if (!initialUrl.isNullOrEmpty() && initialUrl.contains("apkmirror.com") && isVariantDownloadPage(initialUrl)) {
            return initialUrl
        }

        // Case B: initialUrl is a release page
        if (!initialUrl.isNullOrEmpty() && initialUrl.contains("apkmirror.com") && isReleasePage(initialUrl)) {
            onProgress(ApkMirrorDownloadProgress.Resolving("Finding best device variant…"))
            return pickBestVariantFromReleasePage(initialUrl, isBundleRequired)
        }

        // Case C: initialUrl is a search or category/uploads page
        if (!initialUrl.isNullOrEmpty() && initialUrl.contains("apkmirror.com") && isSearchOrCategoryPage(initialUrl)) {
            onProgress(ApkMirrorDownloadProgress.Resolving("Resolving app release from APKMirror page…"))
            val releaseUrl = pickReleaseFromListingsPage(initialUrl, packageName, versionName)
            onProgress(ApkMirrorDownloadProgress.Resolving("Finding best device variant…"))
            return pickBestVariantFromReleasePage(releaseUrl, isBundleRequired)
        }

        // Case D: Resolve release page using app category, predictable slug, or search
        onProgress(ApkMirrorDownloadProgress.Resolving("Finding app release on APKMirror…"))
        val releaseUrl = findReleasePage(packageName, versionName)
        onProgress(ApkMirrorDownloadProgress.Resolving("Finding best device variant…"))
        return pickBestVariantFromReleasePage(releaseUrl, isBundleRequired)
    }

    /**
     * Resolves the release page URL for [packageName] and [versionName].
     * Tries known category endpoints first (which avoid search rate limits),
     * predictable direct URLs next, and falls back to APKMirror search.
     */
    private suspend fun findReleasePage(packageName: String, versionName: String?): String {
        // Strategy 1: Check category uploads page (/uploads/?appcategory=...)
        val categorySlug = APP_CATEGORY_MAP[packageName] ?: packageName.substringAfterLast('.').lowercase()
        val categoryReleases = runCatching {
            fetchCategoryReleases(categorySlug)
        }.getOrNull().orEmpty()

        if (categoryReleases.isNotEmpty()) {
            val match = matchRelease(categoryReleases, versionName)
            if (match != null) return match
        }

        // Strategy 2: If version is known, try predictable release URL pattern
        if (!versionName.isNullOrBlank()) {
            val devSlug = KNOWN_DEV_SLUGS[packageName]
            if (devSlug != null) {
                val appSlug = categorySlug
                val verSlug = versionName.replace('.', '-').replace('_', '-')
                val predictableUrl = "$BASE_URL/apk/$devSlug/$appSlug/$appSlug-$verSlug-release/"
                val reachable = runCatching {
                    val html = fetchHtml(predictableUrl)
                    html.contains("variants-table") || html.contains("downloadButton")
                }.getOrDefault(false)

                if (reachable) {
                    Log.i(tag, "ApkMirrorDownloader: Matched predictable release URL: $predictableUrl")
                    return predictableUrl
                }
            }
        }

        // Strategy 3: Fall back to APKMirror search endpoint
        return searchForReleasePage(packageName, versionName)
    }

    /**
     * Picks release from a search or uploads listings page.
     */
    private suspend fun pickReleaseFromListingsPage(
        pageUrl: String,
        packageName: String,
        versionName: String?
    ): String {
        val html = fetchHtml(pageUrl)
        val releases = parseReleaseLinks(html)
        if (releases.isEmpty()) {
            return findReleasePage(packageName, versionName)
        }

        val matched = matchRelease(releases, versionName)
        return matched ?: releases.first().first
    }

    /**
     * Fetches release links from an app category uploads page.
     */
    private suspend fun fetchCategoryReleases(categorySlug: String): List<Pair<String, String>> {
        val categoryUrl = "$BASE_URL/uploads/?appcategory=$categorySlug"
        val html = fetchHtml(categoryUrl)
        return parseReleaseLinks(html)
    }

    /**
     * Matches the best release from a list of release (url to title) pairs.
     */
    private fun matchRelease(releases: List<Pair<String, String>>, versionName: String?): String? {
        if (releases.isEmpty()) return null

        // 1. Exact or contains match on requested version (matching title or URL slug)
        if (!versionName.isNullOrBlank()) {
            val cleanVer = versionName.trim()
            val urlSlug = cleanVer.replace('.', '-').replace('_', '-')
            val exact = releases.firstOrNull { (url, title) ->
                title.contains(cleanVer, ignoreCase = true) || url.contains(urlSlug, ignoreCase = true)
            }
            if (exact != null) return exact.first

            val strippedVersion = cleanVer.substringBefore('-')
            val strippedUrlSlug = strippedVersion.replace('.', '-').replace('_', '-')
            val partial = releases.firstOrNull { (url, title) ->
                title.contains(strippedVersion, ignoreCase = true) || url.contains(strippedUrlSlug, ignoreCase = true)
            }
            if (partial != null) return partial.first
        }

        // 2. If no version specified, prefer latest non-beta, non-alpha release
        val stable = releases.firstOrNull { (_, title) ->
            val lower = title.lowercase()
            !lower.contains("beta") && !lower.contains("alpha") && !lower.contains("secondary")
        }
        return (stable ?: releases.firstOrNull())?.first
    }

    /**
     * Searches APKMirror for releases matching [packageName] and [versionName].
     */
    private suspend fun searchForReleasePage(packageName: String, versionName: String?): String {
        val query = buildString {
            append(packageName)
            if (!versionName.isNullOrBlank()) {
                append(" ").append(versionName)
            }
        }
        val searchUrl = "$BASE_URL/?post_type=app_release&searchtype=apk&s=${URLEncoder.encode(query, "UTF-8")}"
        val html = fetchHtml(searchUrl)
        val releases = parseReleaseLinks(html)

        if (releases.isEmpty()) {
            // Fallback: search with just package name if version search was too strict
            if (!versionName.isNullOrBlank()) {
                return searchForReleasePage(packageName, null)
            }
            throw IOException("No releases found on APKMirror for $packageName")
        }

        val matched = matchRelease(releases, versionName)
        return matched ?: releases.first().first
    }

    /**
     * Fetches a release page, parses all variants from its table, and picks the most suitable one.
     */
    private suspend fun pickBestVariantFromReleasePage(releaseUrl: String, isBundleRequired: Boolean?): String {
        val html = fetchHtml(releaseUrl, referrer = BASE_URL)
        val variants = parseVariants(html)

        if (variants.isEmpty()) {
            // Some single-variant releases have a direct download button on the release page itself
            val directKeyPage = runCatching { parseDownloadKeyPageUrlFromHtml(html) }.getOrNull()
            if (directKeyPage != null) return directKeyPage
            throw IOException("No compatible variants found on release page: $releaseUrl")
        }

        val bestVariant = selectBestVariant(variants, isBundleRequired)
        return bestVariant.url
    }

    /**
     * Parses the variants table from a release page HTML.
     */
    internal fun parseVariants(html: String): List<ApkMirrorVariant> {
        val variants = mutableListOf<ApkMirrorVariant>()

        // Find variants-table section if present to isolate rows
        val tableStartIndex = html.indexOf("variants-table")
        val scopeHtml = if (tableStartIndex != -1) {
            val nextSection = html.indexOf("class=\"listWidget", tableStartIndex)
            if (nextSection != -1) html.substring(tableStartIndex, nextSection) else html.substring(tableStartIndex)
        } else {
            html
        }

        // Split into table-row segments
        val rowSegments = scopeHtml.split("<div class=\"table-row")
        for (row in rowSegments) {
            if (!row.contains("-android-apk-download")) continue

            val linkMatcher = VARIANT_LINK_REGEX.matcher(row)
            val (variantUrl, version) = if (linkMatcher.find()) {
                resolveUrl(linkMatcher.group(1).orEmpty()) to linkMatcher.group(2).orEmpty().trim()
            } else {
                val hrefMatcher = VARIANT_HREF_REGEX.matcher(row)
                if (hrefMatcher.find()) {
                    val url = resolveUrl(hrefMatcher.group(1).orEmpty())
                    val tagMatcher = VARIANT_ANCHOR_REGEX.matcher(row)
                    val ver = if (tagMatcher.find()) {
                        tagMatcher.group(1).orEmpty().replace(HTML_TAG_REGEX, " ").trim()
                    } else ""
                    url to ver
                } else continue
            }

            val isBundle = row.contains("BUNDLE", ignoreCase = false)

            // Extract table-cell texts
            val cellMatcher = TABLE_CELL_REGEX.matcher(row)
            val cells = mutableListOf<String>()
            while (cellMatcher.find()) {
                val cleanText = cellMatcher.group(1).orEmpty()
                    .replace(HTML_TAG_REGEX, " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                cells.add(cleanText)
            }

            val arch = cells.getOrElse(1) { "universal" }
            val minAndroidVersion = cells.getOrElse(2) { "" }
            val dpi = cells.getOrElse(3) { "nodpi" }

            variants.add(
                ApkMirrorVariant(
                    url = variantUrl,
                    version = version,
                    isBundle = isBundle,
                    arch = arch,
                    minAndroidVersion = minAndroidVersion,
                    dpi = dpi
                )
            )
        }

        return variants
    }

    /**
     * Selects the most optimal variant for the current device and patch preferences.
     */
    internal fun selectBestVariant(
        variants: List<ApkMirrorVariant>,
        isBundleRequired: Boolean?
    ): ApkMirrorVariant {
        var candidateList = variants
        if (isBundleRequired == true) {
            val bundleOnly = candidateList.filter { it.isBundle }
            if (bundleOnly.isNotEmpty()) candidateList = bundleOnly
        } else if (isBundleRequired == false) {
            val apkOnly = candidateList.filter { !it.isBundle }
            if (apkOnly.isNotEmpty()) candidateList = apkOnly
        }

        val supportedAbis = runCatching { Build.SUPPORTED_ABIS?.map { it.lowercase() } }.getOrNull()
            ?.ifEmpty { null }
            ?: listOf("arm64-v8a", "armeabi-v7a")
        val primaryAbi = supportedAbis.firstOrNull() ?: "arm64-v8a"

        return candidateList.maxByOrNull { variant ->
            var score = 0
            val vArch = variant.arch.lowercase()

            when {
                vArch == primaryAbi -> score += 100
                vArch.contains(primaryAbi) -> score += 95
                vArch == "universal" || vArch == "noarch" -> score += 80
                supportedAbis.any { it in vArch } -> score += 60
                else -> score -= 100
            }

            val vDpi = variant.dpi.lowercase()
            if (vDpi == "nodpi" || vDpi == "universal") {
                score += 15
            }

            // Bonus for standard APK when not explicitly requesting bundle
            if (isBundleRequired != true && !variant.isBundle) {
                score += 10
            }

            score
        } ?: variants.first()
    }

    /**
     * Extracts release links from an APKMirror search/uploads page.
     * Strips the sidebar container to avoid picking popular/recent uploads for wrong apps.
     */
    internal fun parseReleaseLinks(html: String): List<Pair<String, String>> {
        val sidebarIndex = html.indexOf("id=\"sidebar\"")
        val mainHtml = if (sidebarIndex != -1) html.substring(0, sidebarIndex) else html

        val matcher = RELEASE_ROW_REGEX.matcher(mainHtml)
        val releases = mutableListOf<Pair<String, String>>()
        while (matcher.find()) {
            val href = matcher.group(1).orEmpty()
            val title = matcher.group(2).orEmpty().trim()
            if (href.contains("/apk/")) {
                releases.add(resolveUrl(href) to title)
            }
        }
        return releases
    }

    /**
     * Extracts the intermediate download key URL from a variant download page.
     */
    private suspend fun extractDownloadKeyPageUrl(variantPageUrl: String): String {
        val html = fetchHtml(variantPageUrl, referrer = BASE_URL)
        return parseDownloadKeyPageUrlFromHtml(html)
            ?: throw IOException("Could not locate download key button on: $variantPageUrl")
    }

    internal fun parseDownloadKeyPageUrlFromHtml(html: String): String? {
        val m1 = DOWNLOAD_KEY_REGEX.matcher(html)
        if (m1.find()) {
            return resolveUrl(unescapeHtml(m1.group(1).orEmpty()))
        }

        val m2 = DOWNLOAD_BUTTON_CLASS_REGEX.matcher(html)
        if (m2.find()) {
            return resolveUrl(unescapeHtml(m2.group(1).orEmpty()))
        }

        val m3 = DOWNLOAD_BUTTON_CLASS_REVERSE_REGEX.matcher(html)
        if (m3.find()) {
            return resolveUrl(unescapeHtml(m3.group(1).orEmpty()))
        }

        return null
    }

    /**
     * Extracts the direct `download.php` endpoint URL from a download key page.
     */
    private suspend fun extractDownloadPhpUrl(keyPageUrl: String): String {
        val html = fetchHtml(keyPageUrl, referrer = keyPageUrl.substringBefore("/download/"))
        return parseDownloadPhpUrlFromHtml(html)
            ?: throw IOException("Could not find download link on: $keyPageUrl")
    }

    internal fun parseDownloadPhpUrlFromHtml(html: String): String? {
        val m1 = DOWNLOAD_LINK_ID_REGEX.matcher(html)
        if (m1.find()) {
            return resolveUrl(unescapeHtml(m1.group(1).orEmpty()))
        }

        val m2 = DOWNLOAD_LINK_ID_REVERSE_REGEX.matcher(html)
        if (m2.find()) {
            return resolveUrl(unescapeHtml(m2.group(1).orEmpty()))
        }

        val m3 = DOWNLOAD_PHP_HREF_REGEX.matcher(html)
        if (m3.find()) {
            return resolveUrl(unescapeHtml(m3.group(1).orEmpty()))
        }

        val m4 = DOWNLOAD_HERE_LINK_REGEX.matcher(html)
        if (m4.find()) {
            return resolveUrl(unescapeHtml(m4.group(1).orEmpty()))
        }

        return null
    }

    /**
     * Streams the binary APK or APKM from [downloadUrl] to a temporary file.
     */
    private suspend fun downloadBinary(
        downloadUrl: String,
        referrer: String,
        packageName: String,
        versionName: String,
        onProgress: suspend (ApkMirrorDownloadProgress) -> Unit
    ): File {
        val tempFolder = filesystem?.uiTempDir?.also { it.mkdirs() }
            ?: error("Filesystem is required for downloadBinary")
        var targetFile: File? = null

        try {
            val cookies = getCookiesForUrl(downloadUrl)
            val statement = client.prepareGet {
                url(downloadUrl)
                timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
                header(HttpHeaders.UserAgent, BROWSER_USER_AGENT)
                header(HttpHeaders.Referrer, referrer)
                header(HttpHeaders.Accept, "*/*")
                header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
                if (!cookies.isNullOrBlank()) {
                    header(HttpHeaders.Cookie, cookies)
                }
            }

            return statement.execute { response ->
                saveCookiesFromResponse(downloadUrl, response)

                if (response.status == HttpStatusCode.Forbidden || response.status == HttpStatusCode.ServiceUnavailable) {
                    val verificationUrl = if (referrer.isNotBlank() && referrer != BASE_URL) referrer else downloadUrl
                    throw CloudflareVerificationException(
                        verificationUrl = verificationUrl,
                        packageName = packageName,
                        versionName = versionName,
                        message = "APKMirror bot verification blocked download stream (HTTP ${response.status})."
                    )
                }

                if (!response.status.isSuccess()) {
                    throw IOException("APKMirror download server responded with HTTP ${response.status}")
                }

                val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                val contentDisposition = response.headers[HttpHeaders.ContentDisposition]
                val contentType = response.headers[HttpHeaders.ContentType]
                val effectiveUrl = response.call.request.url.toString()

                val extension = detectExtension(contentDisposition, effectiveUrl, contentType)
                val safePackage = packageName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val safeVersion = versionName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val destination = tempFolder.resolve("${safePackage}_${safeVersion}_${System.currentTimeMillis()}.$extension")
                targetFile = destination

                val channel: ByteReadChannel = response.body()
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead = 0L
                var lastProgressTime = 0L
                val startTime = System.currentTimeMillis()

                FileOutputStream(destination).use { output ->
                    while (!channel.isClosedForRead) {
                        val read = channel.readAvailable(buffer)
                        if (read < 0) break // EOF reached
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        bytesRead += read

                        val now = System.currentTimeMillis()
                        if (now - lastProgressTime >= PROGRESS_INTERVAL_MS || bytesRead == contentLength) {
                            lastProgressTime = now
                            val elapsedSec = (now - startTime).coerceAtLeast(1) / 1000.0
                            val speed = (bytesRead / elapsedSec).toLong()
                            val progressFraction = if (contentLength != null && contentLength > 0) {
                                (bytesRead.toFloat() / contentLength).coerceIn(0f, 1f)
                            } else null

                            onProgress(
                                ApkMirrorDownloadProgress.Downloading(
                                    bytesDownloaded = bytesRead,
                                    totalBytes = contentLength,
                                    progress = progressFraction,
                                    speedBytesPerSec = speed
                                )
                            )
                        }
                    }
                    output.flush()
                }

                if (destination.length() == 0L) {
                    destination.delete()
                    throw IOException("Downloaded file is empty")
                }

                // If APKMirror returned an HTML challenge instead of binary:
                if (destination.length() < 100_000L) {
                    val sample = runCatching { destination.readBytes().take(1024).toByteArray().decodeToString() }.getOrNull().orEmpty()
                    if (sample.contains("<html", ignoreCase = true) || sample.contains("<!DOCTYPE", ignoreCase = true)) {
                        destination.delete()
                        val verificationUrl = if (referrer.isNotBlank() && referrer != BASE_URL) referrer else downloadUrl
                        if (sample.contains("Cloudflare") || sample.contains("Turnstile") || sample.contains("Just a moment")) {
                            throw CloudflareVerificationException(
                                verificationUrl = verificationUrl,
                                packageName = packageName,
                                versionName = versionName,
                                message = "APKMirror bot verification returned HTML challenge instead of binary."
                            )
                        }
                        throw IOException("APKMirror returned HTML document instead of APK binary.")
                    }
                }

                val isBundle = extension in BUNDLE_EXTENSIONS
                onProgress(ApkMirrorDownloadProgress.Completed(destination, isBundle))
                destination
            }
        } catch (e: Throwable) {
            targetFile?.delete()
            if (e is CancellationException || e is CloudflareVerificationException) throw e
            throw IOException("APK download failed: ${e.message}", e)
        }
    }

    /**
     * Fetches HTML content with proper browser headers and automatic retry on HTTP 429.
     */
    private suspend fun fetchHtml(url: String, referrer: String = BASE_URL): String {
        var attempt = 0
        var delayMs = 1500L
        val maxAttempts = 3

        while (true) {
            attempt++
            try {
                val cookies = getCookiesForUrl(url)
                val response: HttpResponse = client.request(url) {
                    method = HttpMethod.Get
                    header(HttpHeaders.UserAgent, BROWSER_USER_AGENT)
                    header(HttpHeaders.Referrer, referrer)
                    header(HttpHeaders.Accept, "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
                    if (!cookies.isNullOrBlank()) {
                        header(HttpHeaders.Cookie, cookies)
                    }
                    header("Sec-Fetch-Dest", "document")
                    header("Sec-Fetch-Mode", "navigate")
                    header("Sec-Fetch-Site", "same-origin")
                    header("Sec-Fetch-User", "?1")
                    header("Upgrade-Insecure-Requests", "1")
                }

                saveCookiesFromResponse(url, response)

                if (response.status == HttpStatusCode.TooManyRequests) {
                    if (attempt >= maxAttempts) {
                        throw IOException("APKMirror rate limit exceeded (HTTP 429)")
                    }
                    val retryAfter = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.times(1000)
                    val waitTime = (retryAfter ?: delayMs).coerceAtMost(10_000L)
                    Log.w(tag, "ApkMirrorDownloader hit HTTP 429 on $url, retrying in ${waitTime}ms (attempt $attempt/$maxAttempts)")
                    delay(waitTime.milliseconds)
                    delayMs = (delayMs * 2).coerceAtMost(10_000L)
                    continue
                }

                if (response.status == HttpStatusCode.Forbidden || response.status == HttpStatusCode.ServiceUnavailable) {
                    val body = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
                    if (isCloudflareChallenge(body, response)) {
                        throw CloudflareVerificationException(
                            verificationUrl = url,
                            message = "APKMirror bot verification (Cloudflare) is active for this app."
                        )
                    }
                    throw IOException("Failed to load page ($url): HTTP ${response.status}")
                }

                if (!response.status.isSuccess()) {
                    throw IOException("Failed to load page ($url): HTTP ${response.status}")
                }

                val body = response.bodyAsText()
                if (isCloudflareChallenge(body, response)) {
                    throw CloudflareVerificationException(
                        verificationUrl = url,
                        message = "APKMirror bot verification (Cloudflare) is active for this app."
                    )
                }

                return body
            } catch (e: Exception) {
                if (e is CancellationException || e is CloudflareVerificationException) throw e
                if (attempt >= maxAttempts || (e !is IOException && !e.message.orEmpty().contains("429"))) {
                    throw e
                }
                delay(delayMs.milliseconds)
                delayMs = (delayMs * 2).coerceAtMost(10_000L)
            }
        }
    }

    private fun getCookiesForUrl(url: String): String? = runCatching {
        val cm = CookieManager.getInstance()
        val urlCookies = cm.getCookie(url)
        val baseCookies = cm.getCookie(BASE_URL)
        when {
            !urlCookies.isNullOrBlank() && !baseCookies.isNullOrBlank() -> {
                if (urlCookies.contains("cf_clearance")) urlCookies else "$baseCookies; $urlCookies"
            }
            !urlCookies.isNullOrBlank() -> urlCookies
            !baseCookies.isNullOrBlank() -> baseCookies
            else -> null
        }
    }.getOrNull()

    private fun saveCookiesFromResponse(url: String, response: HttpResponse) {
        runCatching {
            val cm = CookieManager.getInstance()
            response.headers.getAll(HttpHeaders.SetCookie)?.forEach { cookie ->
                cm.setCookie(url, cookie)
            }
        }
    }

    private fun isCloudflareChallenge(body: String, response: HttpResponse): Boolean {
        val cfMitigated = response.headers["cf-mitigated"]
        if (cfMitigated.equals("challenge", ignoreCase = true)) return true

        val hasTurnstile = body.contains("challenges.cloudflare.com", ignoreCase = true) ||
            body.contains("cf-turnstile", ignoreCase = true)
        val hasJustAMoment = body.contains("Just a moment...", ignoreCase = true) ||
            body.contains("<title>Just a moment", ignoreCase = true)
        val hasCloudflareChallenge = body.contains("cf-challenge", ignoreCase = true) ||
            body.contains("Attention Required! | Cloudflare", ignoreCase = true) ||
            (body.contains("Cloudflare") && body.contains("Ray ID") && (body.contains("Verify you are human") || body.contains("captcha") || body.contains("challenge")))

        return hasTurnstile || hasJustAMoment || hasCloudflareChallenge
    }

    internal fun isDirectDownloadUrl(url: String): Boolean = Companion.isDirectDownloadUrl(url)

    internal fun isDownloadKeyPage(url: String): Boolean =
        (url.contains("/download/?key=") || url.contains("/download/")) && url.contains("key=")

    internal fun isVariantDownloadPage(url: String): Boolean {
        val cleanPath = url.substringBefore('?').substringBefore('#').trimEnd('/')
        return cleanPath.endsWith("-android-apk-download") && !cleanPath.contains("/download/")
    }

    internal fun isReleasePage(url: String): Boolean {
        val cleanPath = url.substringBefore('?').substringBefore('#').trimEnd('/')
        return cleanPath.endsWith("-release") && !cleanPath.contains("-android-apk-download")
    }

    internal fun isSearchOrCategoryPage(url: String): Boolean =
        url.contains("searchtype=") ||
        url.contains("post_type=app_release") ||
        url.contains("appcategory=") ||
        url.matches(Regex(".*/apk/[^/]+/[^/]+/?$"))

    internal fun detectExtension(contentDisposition: String?, effectiveUrl: String, contentType: String? = null): String {
        if (!contentDisposition.isNullOrBlank()) {
            val matcher = FILENAME_REGEX.matcher(contentDisposition)
            if (matcher.find()) {
                val filename = matcher.group(1).orEmpty().trim('"', '\'')
                val ext = filename.substringAfterLast('.', "").lowercase()
                if (ext in KNOWN_EXTENSIONS) return ext
            }
        }
        val cleanUrl = effectiveUrl.substringBefore('?').substringBefore('#')
        val urlExt = cleanUrl.substringAfterLast('.', "").lowercase()
        if (urlExt in KNOWN_EXTENSIONS) return urlExt

        contentType?.lowercase()?.let { ct ->
            when {
                ct.contains("vnd.apkm") -> return "apkm"
                ct.contains("vnd.android.package-archive") -> return "apk"
                ct.contains("zip") || ct.contains("xapk") -> return "xapk"
            }
        }

        return "apk"
    }

    internal fun resolveUrl(path: String): String = when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("/") -> "$BASE_URL$path"
        else -> "$BASE_URL/$path"
    }

    internal fun unescapeHtml(text: String): String =
        text.replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&#38;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")

    companion object {
        const val BASE_URL = "https://www.apkmirror.com"
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_INTERVAL_MS = 150L
        private const val INTER_HOP_DELAY_MS = 600L

        fun isDirectDownloadUrl(url: String): Boolean =
            url.contains("download.php") ||
            url.contains("r2.cloudflarestorage.com") ||
            url.substringBefore('?').substringBefore('#').endsWith(".apk") ||
            url.substringBefore('?').substringBefore('#').endsWith(".apkm") ||
            url.substringBefore('?').substringBefore('#').endsWith(".xapk")

        private val BUNDLE_EXTENSIONS = setOf("apkm", "xapk", "apks")
        private val KNOWN_EXTENSIONS = setOf("apk", "apkm", "xapk", "apks")

        /**
         * Stable mappings from known package names to APKMirror category slugs.
         * Using uploads/?appcategory= bypasses strict search rate-limiting.
         */
        private val APP_CATEGORY_MAP = mapOf(
            "com.google.android.youtube" to "youtube",
            "com.google.android.apps.youtube.music" to "youtube-music",
            "com.reddit.frontpage" to "reddit",
            "com.twitter.android" to "twitter",
            "tv.twitch.android.app" to "twitch",
            "com.whatsapp" to "whatsapp",
            "com.instagram.android" to "instagram",
            "com.zhiliaoapp.musically" to "tik-tok",
            "com.ss.android.ugc.trill" to "tik-tok"
        )

        private val KNOWN_DEV_SLUGS = mapOf(
            "com.google.android.youtube" to "google-inc",
            "com.google.android.apps.youtube.music" to "google-inc",
            "com.reddit.frontpage" to "redditinc",
            "com.twitter.android" to "x-corp"
        )

        private val RELEASE_ROW_REGEX = Pattern.compile(
            """<h5[^>]*class="[^"]*appRowTitle[^"]*"[^>]*>\s*<a[^>]*class="fontBlack"[^>]*href="([^"]+)"[^>]*>([^<]+)</a>""",
            Pattern.CASE_INSENSITIVE
        )

        private val VARIANT_LINK_REGEX = Pattern.compile(
            """<a[^>]*class="accent_color"[^>]*href="([^"]*-android-apk-download/)">\s*(?:<svg[^>]*>.*?</svg>\s*)?([^<]+)</a>""",
            Pattern.DOTALL or Pattern.CASE_INSENSITIVE
        )

        private val VARIANT_HREF_REGEX = Pattern.compile(
            """href="([^"]*-android-apk-download/?)"""",
            Pattern.CASE_INSENSITIVE
        )

        private val VARIANT_ANCHOR_REGEX = Pattern.compile(
            """<a[^>]*href="[^"]*-android-apk-download/?"[^>]*>(.*?)</a>""",
            Pattern.DOTALL or Pattern.CASE_INSENSITIVE
        )

        private val TABLE_CELL_REGEX = Pattern.compile(
            """<div class="table-cell[^"]*">(.*?)</div>""",
            Pattern.DOTALL or Pattern.CASE_INSENSITIVE
        )

        private val HTML_TAG_REGEX = Regex("<[^>]+>")

        private val DOWNLOAD_KEY_REGEX = Pattern.compile(
            """href=["']([^"']*/download/\?key=[^"']+)["']""",
            Pattern.CASE_INSENSITIVE
        )

        private val DOWNLOAD_BUTTON_CLASS_REGEX = Pattern.compile(
            """class=["'][^"']*downloadButton[^"']*["'][^>]*href=["']([^"']+)["']""",
            Pattern.CASE_INSENSITIVE
        )

        private val DOWNLOAD_BUTTON_CLASS_REVERSE_REGEX = Pattern.compile(
            """<a[^>]*href=["']([^"']+)["'][^>]*class=["'][^"']*downloadButton[^"']*["']""",
            Pattern.CASE_INSENSITIVE
        )

        private val DOWNLOAD_LINK_ID_REGEX = Pattern.compile(
            """<a[^>]*id=["']download-link["'][^>]*href=["']([^"']+)["']""",
            Pattern.CASE_INSENSITIVE
        )

        private val DOWNLOAD_LINK_ID_REVERSE_REGEX = Pattern.compile(
            """<a[^>]*href=["']([^"']+)["'][^>]*id=["']download-link["']""",
            Pattern.CASE_INSENSITIVE
        )

        private val DOWNLOAD_PHP_HREF_REGEX = Pattern.compile(
            """href=["']([^"']*download\.php[^"']*)["']""",
            Pattern.CASE_INSENSITIVE
        )

        private val DOWNLOAD_HERE_LINK_REGEX = Pattern.compile(
            """<a[^>]+href=["']([^"']+)["'][^>]*>\s*here\s*</a>""",
            Pattern.CASE_INSENSITIVE
        )

        private val FILENAME_REGEX = Pattern.compile(
            """filename\*?=(?:UTF-8'')?["']?([^"';\r\n]+)""",
            Pattern.CASE_INSENSITIVE
        )
    }
}
