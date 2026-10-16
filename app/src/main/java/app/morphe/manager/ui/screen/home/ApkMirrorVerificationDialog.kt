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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
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
import app.morphe.manager.ui.screen.shared.*

/**
 * In-app WebView verification dialog for APKMirror Cloudflare verification and downloads.
 *
 * Renders an embedded browser dialog allowing the user to complete verification prompts
 * or initiate the download without needing to switch back and forth to an external browser.
 *
 * Once verified by the user or when a download link is triggered, captures cookies into [CookieManager]
 * and returns the direct download stream to Morphe's in-built download and patching pipeline.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ApkMirrorVerificationDialog(
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
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    AppDialog(
        onDismissRequest = onDismissRequest,
        accentColor = accentColor,
        title = stringResource(R.string.home_download_inbuilt_verification_title),
        description = stringResource(R.string.home_download_inbuilt_verification_description),
        scrollable = false,
        fillContentHeight = false,
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
                        onVerificationCompleted()
                    },
                    icon = Icons.Outlined.Check,
                    modifier = Modifier.fillMaxWidth()
                )

                AppDialogOutlinedButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = onDismissRequest,
                    icon = Icons.Outlined.Close,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .clip(RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
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
                            setDownloadListener { url, _, _, _, _ ->
                                runCatching { CookieManager.getInstance().flush() }
                                onDownloadReady(url)
                            }
                            webViewClient = object : WebViewClient() {
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
                                    val cookies = runCatching {
                                        CookieManager.getInstance().getCookie("https://www.apkmirror.com").orEmpty()
                                    }.getOrDefault("")
                                    val hasCfClearance = cookies.contains("cf_clearance")
                                    val isChallengeTitle = title.contains("Just a moment", ignoreCase = true) ||
                                        title.contains("Cloudflare", ignoreCase = true) ||
                                        title.contains("Attention Required", ignoreCase = true)
                                    val isChallengeUrl = url?.contains("challenges.cloudflare.com") == true ||
                                        url?.contains("cf-mitigated") == true

                                    val isContentPage = url?.contains("apkmirror.com") == true && !isChallengeTitle && !isChallengeUrl

                                    if (hasCfClearance || isContentPage) {
                                        isSolved = true
                                        runCatching { CookieManager.getInstance().flush() }

                                        // Try auto-clicking download button if present
                                        view?.evaluateJavascript(
                                            """
                                            (function() {
                                                var btn = document.querySelector('a.downloadButton, a#download-link, a[href*="download.php"]');
                                                if (btn) {
                                                    btn.click();
                                                    return true;
                                                }
                                                return false;
                                            })();
                                            """.trimIndent(),
                                            null
                                        )
                                    }
                                }
                            }
                            webViewRef = this
                            loadUrl(verificationUrl)
                        }
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
