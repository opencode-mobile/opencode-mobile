package dev.opencode.hexcode.home

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.HttpAuthHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import dev.opencode.hexcode.R
import dev.opencode.hexcode.designsystem.components.HexBrowser
import dev.opencode.hexcode.designsystem.components.HexBrowserError
import dev.opencode.hexcode.designsystem.components.HexBrowserLabels
import dev.opencode.hexcode.designsystem.components.HexSheetAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream

/**
 * The in-app browser's artifact mode (m6-proposal.md §2.4; app-plan.md, Artifacts (M8) › Using it): the web view opens
 * `<address>/api/experimental/artifacts/<id>/view/`, and every request under it is answered by the app, which fetches
 * the file with the kit's signed-in client ([fetcher]) and passes its status, type and headers through. The web view
 * never holds the credentials, and no password prompt can appear. Only the main page's answer counts for the errors;
 * a `401` closes the browser and home shows Signed out ([onSignedOut]). No "Open in browser": the system browser can't
 * sign in.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ArtifactBrowser(
    page: ArtifactPage,
    /** The address in use, `http://192.168.1.20:4096`. */
    address: String,
    fetcher: ArtifactFetcher,
    computerName: String,
    onClose: () -> Unit,
    onSignedOut: () -> Unit,
    /** The `403` error's Turn on…: the enable sheet. */
    onTurnOn: () -> Unit,
) {
    val context = LocalContext.current
    val where = remember(address, page.id) { ArtifactAddress(address, page.id) }
    var title by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var loading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<ArtifactFailure?>(null) }
    val signedOut by rememberUpdatedState(onSignedOut)
    val main = remember { Handler(Looper.getMainLooper()) }
    val webView =
        remember {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // The page draws itself as it is: no forced dark mode.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) settings.isAlgorithmicDarkeningAllowed = false
                webViewClient =
                    object : WebViewClient() {
                        // Runs off the main thread: each file of the artifact comes through the app's own client.
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            val path = where.serverPath(request.url.toString()) ?: return null
                            val file = runBlocking(Dispatchers.IO) { fetcher.fetch(path) }
                            if (request.isForMainFrame) {
                                val failed = ArtifactFailure.of(file?.status)
                                main.post {
                                    if (failed == ArtifactFailure.SignedOut) signedOut() else failure = failed
                                }
                            }
                            return response(file)
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val target = request.url.toString()
                            // The artifact's own pages stay; anything else goes to the system.
                            if (where.serverPath(target) != null) return false
                            openOutside(view.context, target)
                            return true
                        }

                        // A challenge can only come from outside the artifact: there's never a password prompt.
                        override fun onReceivedHttpAuthRequest(
                            view: WebView,
                            handler: HttpAuthHandler,
                            host: String?,
                            realm: String?,
                        ) = handler.cancel()

                        override fun onPageStarted(
                            view: WebView,
                            url: String?,
                            favicon: Bitmap?,
                        ) {
                            loading = true
                        }

                        override fun onPageFinished(
                            view: WebView,
                            url: String?,
                        ) {
                            loading = false
                        }

                        override fun doUpdateVisitedHistory(
                            view: WebView,
                            url: String?,
                            isReload: Boolean,
                        ) {
                            canGoBack = view.canGoBack()
                            canGoForward = view.canGoForward()
                        }
                    }
                webChromeClient =
                    object : WebChromeClient() {
                        override fun onProgressChanged(
                            view: WebView,
                            newProgress: Int,
                        ) {
                            progress = newProgress / 100f
                        }

                        override fun onReceivedTitle(
                            view: WebView,
                            received: String?,
                        ) {
                            // A page with no <title> (or an error's answer) reports its address, with or without the scheme;
                            // the tile's title reads better then.
                            val address = view.url?.substringAfter("://")
                            title =
                                received?.takeIf {
                                    it.isNotBlank() &&
                                        !it.startsWith("http") &&
                                        address?.startsWith(it.removeSuffix("…")) != true
                                }
                        }
                    }
                loadUrl(where.start)
            }
        }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
    // Back goes back in the page's history, then closes.
    BackHandler { if (webView.canGoBack()) webView.goBack() else onClose() }
    val retry = {
        failure = null
        webView.reload()
    }
    val done = stringResource(R.string.common_done)
    HexBrowser(
        title = title ?: page.title,
        host = stringResource(R.string.browser_artifact, computerName),
        progress = if (loading && failure == null) progress else null,
        canGoBack = canGoBack,
        canGoForward = canGoForward,
        labels =
            HexBrowserLabels(
                done = done,
                reload = stringResource(R.string.browser_reload),
                back = stringResource(R.string.browser_back),
                forward = stringResource(R.string.browser_forward),
                openSystem = null,
                loading = stringResource(R.string.browser_loading),
            ),
        onDone = onClose,
        onReload = retry,
        onBack = { if (webView.canGoBack()) webView.goBack() },
        onForward = { if (webView.canGoForward()) webView.goForward() },
        onOpenSystem = {},
        error =
            when (val failed = failure) {
                ArtifactFailure.Unreachable ->
                    HexBrowserError(
                        stringResource(R.string.browser_unreachable, computerName),
                        stringResource(R.string.browser_unreachable_sub),
                        stringResource(R.string.browser_try_again),
                        retry,
                    )
                ArtifactFailure.Gone ->
                    HexBrowserError(
                        stringResource(R.string.browser_gone),
                        stringResource(R.string.browser_gone_sub),
                        done,
                        onClose,
                        icon = null,
                    )
                ArtifactFailure.Off ->
                    HexBrowserError(
                        stringResource(R.string.browser_off, computerName),
                        stringResource(R.string.browser_off_sub).replace("`", ""),
                        done,
                        onClose,
                        icon = null,
                        second = HexSheetAction(stringResource(R.string.browser_off_turn_on), onTurnOn),
                    )
                is ArtifactFailure.Failed ->
                    HexBrowserError(
                        stringResource(R.string.browser_failed),
                        stringResource(R.string.browser_failed_sub, failed.status),
                        stringResource(R.string.browser_try_again),
                        retry,
                    )
                ArtifactFailure.SignedOut, null -> null
            },
    ) {
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
    }
}

/**
 * The web view's answer for one file: the server's status, type and headers as they came; nothing reached it, an empty
 * `502`. A redirect can't be answered here (the client already followed it), so it counts as a `502` too.
 */
private fun response(file: ArtifactFile?): WebResourceResponse {
    if (file == null || file.status !in 100..599 || file.status in 300..399) {
        return WebResourceResponse("text/plain", "utf-8", 502, "Bad Gateway", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
    return WebResourceResponse(
        file.webMime,
        file.charset,
        file.status,
        REASONS[file.status] ?: "Status ${file.status}",
        file.passedHeaders,
        ByteArrayInputStream(file.body),
    )
}

private val REASONS =
    mapOf(
        200 to "OK",
        204 to "No Content",
        304 to "Not Modified",
        400 to "Bad Request",
        401 to "Unauthorized",
        403 to "Forbidden",
        404 to "Not Found",
        500 to "Internal Server Error",
        503 to "Service Unavailable",
    )

/** A link the in-app browser doesn't keep: the system browser, mail or phone takes it. */
private fun openOutside(
    context: Context,
    url: String,
) {
    val intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    try {
        context.startActivity(intent)
    } catch (error: ActivityNotFoundException) {
        Log.w(TalkLog.TAG, "nothing opens $url", error)
    }
}
