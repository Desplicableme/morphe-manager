/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.home

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.morphe.manager.R
import app.morphe.manager.network.apkmirror.ApkMirrorDownloader
import app.morphe.manager.network.apkmirror.HeadlessApkMirrorVerifier
import app.morphe.manager.ui.screen.shared.*
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.Check
import com.composables.icons.materialsymbols.rounded.Close

/**
 * In-app WebView verification dialog for APKMirror Cloudflare verification and downloads (ReVanced Manager style).
 *
 * Renders a single, steady embedded browser dialog allowing the user to complete verification prompts
 * or initiate the download without rapid opening/closing or leaving the application.
 *
 * Once verified or when a download link is triggered, captures cookies into [CookieManager]
 * and returns the direct download stream to Morphe's in-built download and patching pipeline.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun DialogHostScope.ApkMirrorVerificationDialog(
    verificationUrl: String,
    packageName: String?,
    appName: String?,
    onDismissRequest: () -> Unit,
    onDownloadReady: (directUrl: String) -> Unit,
    onVerificationCompleted: () -> Unit
) {
    val appColor = rememberAppColor(packageName)
    val accentColor = appColor ?: MaterialTheme.colorScheme.primary

    var isLoading by remember { mutableStateOf(true) }
    var isSolved by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                (parent as? ViewGroup)?.removeView(this)
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            webViewRef = null
        }
    }

    AppDialog(
        onDismissRequest = onDismissRequest,
        accentColor = accentColor,
        title = stringResource(R.string.home_download_inbuilt_verification_title),
        description = stringResource(R.string.home_download_inbuilt_verification_description),
        scrollable = false,
        footer = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppDialogButton(
                    text = if (isSolved) {
                        stringResource(R.string.home_download_inbuilt_verification_solved)
                    } else {
                        stringResource(R.string.home_download_inbuilt_verification_continue)
                    },
                    onClick = {
                        runCatching { CookieManager.getInstance().flush() }
                        webViewRef?.evaluateJavascript(
                            """
                            (function() {
                                var dl = document.querySelector('a#download-link, a[href*="download.php"]');
                                if (dl && dl.href) return dl.href;
                                var btn = document.querySelector('a.downloadButton, a[href*="/download/?key="]');
                                if (btn) { btn.click(); return 'clicked'; }
                                return '';
                            })();
                            """.trimIndent()
                        ) { result ->
                            val directUrl = result?.trim('"', '\'').orEmpty()
                            if (directUrl.isNotBlank() && directUrl != "clicked" && ApkMirrorDownloader.isDirectDownloadUrl(directUrl)) {
                                onDownloadReady(directUrl)
                            } else {
                                onVerificationCompleted()
                            }
                        }
                    },
                    icon = MaterialSymbols.Rounded.Check,
                    modifier = Modifier.fillMaxWidth()
                )

                AppDialogOutlinedButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = onDismissRequest,
                    icon = MaterialSymbols.Rounded.Close,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(460.dp)
                .clip(RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        val wv = WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                userAgentString = ApkMirrorDownloader.BROWSER_USER_AGENT
                            }
                            runCatching {
                                val cookieManager = CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)
                                cookieManager.setAcceptThirdPartyCookies(this@apply, true)
                            }
                        }

                        wv.setDownloadListener { url, _, _, _, _ ->
                            runCatching { CookieManager.getInstance().flush() }
                            onDownloadReady(url)
                        }

                        wv.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val reqUrl = request?.url?.toString() ?: return false
                                if (ApkMirrorDownloader.isDirectDownloadUrl(reqUrl)) {
                                    runCatching { CookieManager.getInstance().flush() }
                                    onDownloadReady(reqUrl)
                                    return true
                                }
                                return false
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                val title = view?.title.orEmpty()
                                val pageUrl = url ?: view?.url.orEmpty()
                                val cookies = runCatching {
                                    CookieManager.getInstance().getCookie("https://www.apkmirror.com").orEmpty()
                                }.getOrDefault("")
                                val hasCfClearance = cookies.contains("cf_clearance")
                                val isChallenge = HeadlessApkMirrorVerifier.isChallenge(title, pageUrl)

                                if (!isChallenge || hasCfClearance) {
                                    isSolved = true
                                    runCatching { CookieManager.getInstance().flush() }
                                    view?.evaluateJavascript(HeadlessApkMirrorVerifier.AUTO_CLICK_JS, null)
                                }
                            }
                        }

                        wv.loadUrl(verificationUrl)
                        webViewRef = wv
                        wv
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                        color = accentColor
                    )
                }
            }
        }
    }
}
