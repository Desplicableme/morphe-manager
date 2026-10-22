/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.network.apkmirror

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Result of headless APKMirror verification.
 */
sealed interface HeadlessVerificationResult {
    /**
     * Silent verification passed without user interaction.
     * @param directDownloadUrl Direct binary or download.php URL intercepted if auto-click succeeded, or null if cf_clearance was obtained.
     */
    data class Success(val directDownloadUrl: String? = null) : HeadlessVerificationResult

    /**
     * Silent verification did not resolve within timeout, requiring manual user interaction (e.g. Turnstile checkbox).
     */
    data object RequiresInteraction : HeadlessVerificationResult

    /**
     * Verification encountered an unexpected error.
     */
    data class Failed(val cause: Throwable? = null) : HeadlessVerificationResult
}

/**
 * Headless verifier that runs an offscreen [WebView] on [Dispatchers.Main] to silently pass
 * Cloudflare bot mitigation (Turnstile / managed challenges) for APKMirror in the background.
 */
object HeadlessApkMirrorVerifier {
    private const val TAG = "HeadlessApkMirrorVerifier"

    internal const val AUTO_CLICK_JS = """
        (function() {
            var btn = document.querySelector('a.downloadButton, a#download-link, a[href*="download.php"]');
            if (btn) {
                btn.click();
                return true;
            }
            return false;
        })();
    """

    internal fun isChallenge(title: String, url: String): Boolean {
        val isChallengeTitle = title.contains("Just a moment", ignoreCase = true) ||
            title.contains("Cloudflare", ignoreCase = true) ||
            title.contains("Attention Required", ignoreCase = true)
        val isChallengeUrl = url.contains("challenges.cloudflare.com") ||
            url.contains("cf-mitigated")
        return isChallengeTitle || isChallengeUrl
    }

    internal fun isContentPage(title: String, url: String): Boolean =
        url.contains("apkmirror.com") && !isChallenge(title, url)

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun verify(
        context: Context,
        verificationUrl: String,
        timeoutMs: Long = 8000L
    ): HeadlessVerificationResult = withContext(Dispatchers.Main) {
        var webView: WebView? = null
        val deferred = CompletableDeferred<HeadlessVerificationResult>()

        try {
            val result = withTimeoutOrNull(timeoutMs) {
                if (ApkMirrorDownloader.isDirectDownloadUrl(verificationUrl)) {
                    runCatching { CookieManager.getInstance().flush() }
                    return@withTimeoutOrNull HeadlessVerificationResult.Success(verificationUrl)
                }

                val wv = WebView(context).apply {
                    layout(0, 0, 1080, 1920)

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        userAgentString = ApkMirrorDownloader.BROWSER_USER_AGENT
                    }

                    runCatching {
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this@apply, true)
                    }

                    setDownloadListener { url, _, _, _, _ ->
                        Log.i(TAG, "HeadlessApkMirrorVerifier: Intercepted download via setDownloadListener: $url")
                        runCatching { CookieManager.getInstance().flush() }
                        deferred.complete(HeadlessVerificationResult.Success(url))
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val reqUrl = request?.url?.toString() ?: return false
                            if (ApkMirrorDownloader.isDirectDownloadUrl(reqUrl)) {
                                Log.i(TAG, "HeadlessApkMirrorVerifier: Intercepted download via shouldOverrideUrlLoading: $reqUrl")
                                runCatching { CookieManager.getInstance().flush() }
                                deferred.complete(HeadlessVerificationResult.Success(reqUrl))
                                return true
                            }
                            return false
                        }

                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            val reqUrl = url ?: return false
                            if (ApkMirrorDownloader.isDirectDownloadUrl(reqUrl)) {
                                Log.i(TAG, "HeadlessApkMirrorVerifier: Intercepted download via legacy shouldOverrideUrlLoading: $reqUrl")
                                runCatching { CookieManager.getInstance().flush() }
                                deferred.complete(HeadlessVerificationResult.Success(reqUrl))
                                return true
                            }
                            return false
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (deferred.isCompleted) return

                            val title = view?.title.orEmpty()
                            val pageUrl = url ?: view?.url.orEmpty()

                            if (ApkMirrorDownloader.isDirectDownloadUrl(pageUrl)) {
                                Log.i(TAG, "HeadlessApkMirrorVerifier: Page finished on direct URL: $pageUrl")
                                runCatching { CookieManager.getInstance().flush() }
                                deferred.complete(HeadlessVerificationResult.Success(pageUrl))
                                return
                            }

                            val cm = CookieManager.getInstance()
                            val cookies = runCatching {
                                cm.getCookie(ApkMirrorDownloader.BASE_URL).orEmpty() + "; " + cm.getCookie(pageUrl).orEmpty()
                            }.getOrDefault("")
                            val hasCfClearance = cookies.contains("cf_clearance")
                            val isContent = isContentPage(title, pageUrl)

                            if (hasCfClearance || isContent) {
                                Log.i(TAG, "HeadlessApkMirrorVerifier: Verification passed (cfClearance=$hasCfClearance, isContent=$isContent)")
                                runCatching { cm.flush() }
                                view?.evaluateJavascript(AUTO_CLICK_JS) { clickResult ->
                                    if (deferred.isCompleted) return@evaluateJavascript
                                    val clicked = clickResult == "true"
                                    if (clicked) {
                                        launch {
                                            delay(1000L)
                                            deferred.complete(HeadlessVerificationResult.Success(null))
                                        }
                                    } else {
                                        deferred.complete(HeadlessVerificationResult.Success(null))
                                    }
                                }
                            }
                        }
                    }

                    loadUrl(verificationUrl)
                }
                webView = wv

                val pollJob = launch {
                    var clearanceSeenTime = 0L
                    while (isActive && !deferred.isCompleted) {
                        delay(500L)
                        val cm = CookieManager.getInstance()
                        val cookies = runCatching {
                            cm.getCookie(ApkMirrorDownloader.BASE_URL).orEmpty() + "; " + cm.getCookie(wv.url.orEmpty()).orEmpty()
                        }.getOrDefault("")

                        if (cookies.contains("cf_clearance")) {
                            runCatching { cm.flush() }
                            val title = wv.title.orEmpty()
                            val pageUrl = wv.url.orEmpty()
                            val challengeActive = isChallenge(title, pageUrl)

                            if (!challengeActive) {
                                wv.evaluateJavascript(AUTO_CLICK_JS) { clickResult ->
                                    if (deferred.isCompleted) return@evaluateJavascript
                                    val clicked = clickResult == "true"
                                    if (clicked) {
                                        launch {
                                            delay(1000L)
                                            deferred.complete(HeadlessVerificationResult.Success(null))
                                        }
                                    } else {
                                        deferred.complete(HeadlessVerificationResult.Success(null))
                                    }
                                }
                                break
                            } else {
                                if (clearanceSeenTime == 0L) {
                                    clearanceSeenTime = System.currentTimeMillis()
                                } else if (System.currentTimeMillis() - clearanceSeenTime > 2000L) {
                                    Log.i(TAG, "HeadlessApkMirrorVerifier: cf_clearance present for >2s, completing success")
                                    deferred.complete(HeadlessVerificationResult.Success(null))
                                    break
                                }
                            }
                        }
                    }
                }

                val resolution = deferred.await()
                pollJob.cancel()
                resolution
            }

            result ?: HeadlessVerificationResult.RequiresInteraction
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "HeadlessApkMirrorVerifier: Verification failed with exception", e)
            HeadlessVerificationResult.Failed(e)
        } finally {
            runCatching {
                webView?.apply {
                    stopLoading()
                    loadUrl("about:blank")
                    webViewClient = WebViewClient()
                    setDownloadListener(null)
                    destroy()
                }
            }
            webView = null
        }
    }
}
