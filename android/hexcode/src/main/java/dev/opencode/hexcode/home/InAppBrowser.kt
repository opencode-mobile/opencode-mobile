package dev.opencode.hexcode.home

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import dev.opencode.hexcode.R
import dev.opencode.hexcode.designsystem.components.HexBrowser
import dev.opencode.hexcode.designsystem.components.HexBrowserError
import dev.opencode.hexcode.designsystem.components.HexBrowserLabels
import dev.opencode.hexcode.session.SummaryLinks
import java.net.URI

/** A `localhost` tile to open in the in-app browser: its [url] already on the computer's address, and its [label]. */
data class BrowserPage(
    val url: String,
    val label: String,
)

/** What the in-app browser does with a link inside the page (app-plan.md, The in-app browser › Inside the page). */
enum class BrowserNavigation {
    /** The same origin: it stays in the browser. */
    Stay,

    /** Another `http` or `https` origin, or another scheme (`mailto:`, `tel:`): the system takes it. */
    System,
    ;

    companion object {
        fun of(
            start: String,
            target: String,
        ): BrowserNavigation {
            val from = origin(start) ?: return System
            val to = origin(target) ?: return System
            return if (from == to) Stay else System
        }

        /** `http://192.168.0.47:5173`: the scheme, host and port, with the scheme's default port filled in. */
        private fun origin(url: String): Triple<String, String, Int>? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host?.lowercase() ?: return null
            val port =
                if (uri.port >= 0) {
                    uri.port
                } else if (scheme == "https") {
                    443
                } else {
                    80
                }
            return Triple(scheme, host, port)
        }
    }
}

/** Why the main page didn't load (app-plan.md, The in-app browser › Errors). */
enum class BrowserFailure {
    /** The computer answered but nothing listens on the port: a dev server listening only on the computer itself. */
    DevServer,

    /** The computer itself couldn't be reached. */
    Computer,
    ;

    companion object {
        /** From [WebViewClient]'s error codes; a dev server's own `4xx` or `5xx` page is no failure at all. */
        fun of(code: Int): BrowserFailure =
            when (code) {
                WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_TIMEOUT -> Computer
                else -> DevServer
            }
    }
}

/**
 * The in-app browser for `localhost` tiles (app-plan.md, The in-app browser): a [WebView] at the computer's address,
 * with no credentials, over home until Done or Back past the first page. Links to the same origin stay; others go to
 * the system. `alert`, `confirm` and `prompt` show the WebView's system dialogs; `target=_blank` opens in the same
 * view. Website data is kept between opens; Forget this computer clears it ([BrowserData]).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InAppBrowser(
    page: BrowserPage,
    computerName: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var loading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<BrowserFailure?>(null) }
    val webView =
        remember {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // The page draws itself as it is: no forced dark mode.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) settings.isAlgorithmicDarkeningAllowed = false
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val target = request.url.toString()
                            if (BrowserNavigation.of(page.url, target) == BrowserNavigation.Stay) return false
                            openOutside(view.context, target)
                            return true
                        }

                        override fun onPageStarted(
                            view: WebView,
                            url: String?,
                            favicon: Bitmap?,
                        ) {
                            loading = true
                            failure = null
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

                        // Only the main page's errors count; a failed image or script leaves the page as it is.
                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (!request.isForMainFrame) return
                            Log.i(TalkLog.TAG, "browser: ${error.errorCode} ${error.description} for ${request.url}")
                            failure = BrowserFailure.of(error.errorCode)
                            // The WebView's own error page has a title of its own ("Webpage not available").
                            title = null
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
                            // A page with no <title> reports its address; the tile's label reads better then.
                            if (failure == null) received?.takeIf { it.isNotBlank() && !it.startsWith("http") }?.let { title = it }
                        }
                    }
                loadUrl(page.url)
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
    val host = SummaryLinks.host(page.url)
    val port = runCatching { URI(page.url).port }.getOrNull()?.takeIf { it >= 0 } ?: 80
    val retry = { webView.reload() }
    HexBrowser(
        title = title ?: page.label,
        host = host,
        progress = if (loading && failure == null) progress else null,
        canGoBack = canGoBack,
        canGoForward = canGoForward,
        labels =
            HexBrowserLabels(
                done = stringResource(R.string.common_done),
                reload = stringResource(R.string.browser_reload),
                back = stringResource(R.string.browser_back),
                forward = stringResource(R.string.browser_forward),
                openSystem = stringResource(R.string.browser_open_system),
                loading = stringResource(R.string.browser_loading),
            ),
        onDone = onClose,
        onReload = retry,
        onBack = { if (webView.canGoBack()) webView.goBack() },
        onForward = { if (webView.canGoForward()) webView.goForward() },
        onOpenSystem = { openOutside(context, webView.url ?: page.url) },
        error =
            when (failure) {
                BrowserFailure.DevServer ->
                    HexBrowserError(
                        stringResource(R.string.browser_dev_unreachable, port, computerName),
                        stringResource(R.string.browser_dev_unreachable_sub).replace("`", ""),
                        stringResource(R.string.browser_try_again),
                        retry,
                    )
                BrowserFailure.Computer ->
                    HexBrowserError(
                        stringResource(R.string.browser_unreachable, computerName),
                        stringResource(R.string.browser_unreachable_sub),
                        stringResource(R.string.browser_try_again),
                        retry,
                    )
                null -> null
            },
    ) {
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
    }
}

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

/** The in-app browser's website data: kept between opens (the default store), cleared when the computer is forgotten. */
object BrowserData {
    /** Cookies, storage and the cache. Call on the main thread. */
    fun clear(context: Context) {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        runCatching { WebView(context).apply { clearCache(true) }.destroy() }
    }
}
