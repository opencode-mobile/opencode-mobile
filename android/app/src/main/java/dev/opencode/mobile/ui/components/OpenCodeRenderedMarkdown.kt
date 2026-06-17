package dev.opencode.mobile.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.opencode.mobile.theme.ThemeManager
import org.json.JSONObject
import kotlin.math.abs

enum class OpenCodeRenderedMarkdownVariant(
    val wireName: String,
) {
    AssistantMessage("assistantMessage"),
    OutgoingMessage("outgoingMessage"),
    Standalone("standalone"),
}

object OpenCodeRenderedMarkdownDetector {
    private val markdownPattern =
        Regex(
            pattern =
                """(?m)(^#{1,6}\s)|(^\s*[-*+]\s+)|(^\s*\d+[.)]\s+)|(^>\s)|(^```)|(^~~~)|(^\s*\|.+\|\s*$)|(^\s{4,}\S)|(^\s*(-{3,}|\*{3,}|_{3,})\s*$)""",
        )

    fun shouldRender(text: String): Boolean = markdownPattern.containsMatchIn(text)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OpenCodeRenderedMarkdown(
    markdown: String,
    variant: OpenCodeRenderedMarkdownVariant,
    modifier: Modifier = Modifier,
    initialHeight: Dp = 1.dp,
    isStreaming: Boolean = false,
    collapsedMaxHeight: Dp? = null,
    expanded: Boolean = true,
    onExpandableChange: (Boolean) -> Unit = {},
    onContentHeightChange: (Dp) -> Unit = {},
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val currentMarkdown by rememberUpdatedState(markdown)
    val currentVariant by rememberUpdatedState(variant)
    val currentStreaming by rememberUpdatedState(isStreaming)
    val currentExpandableChange by rememberUpdatedState(onExpandableChange)
    val currentContentHeightChange by rememberUpdatedState(onContentHeightChange)
    val darkTheme = isSystemInDarkTheme()
    val tokens = if (darkTheme) ThemeManager.resolvedDark.tokens else ThemeManager.resolvedLight.tokens
    val html = remember { RenderedMarkdownWebRuntime.html(context) }
    var contentHeight by remember { mutableStateOf(initialHeight.coerceAtLeast(1.dp)) }
    val visibleHeight =
        if (!expanded && collapsedMaxHeight != null && contentHeight > collapsedMaxHeight) {
            collapsedMaxHeight
        } else {
            contentHeight
        }

    fun renderId(): String {
        val tokenSignature =
            tokens
                .toSortedMap()
                .entries
                .joinToString(separator = "\u001f") { "${it.key}=${it.value}" }
                .hashCode()
                .toString()
        return listOf(
            currentVariant.wireName,
            if (currentStreaming) "streaming" else "settled",
            currentMarkdown.hashCode().toString(),
            tokenSignature,
        ).joinToString(separator = ":")
    }

    fun payload(renderId: String): String =
        JSONObject()
            .put("markdown", currentMarkdown)
            .put("variant", currentVariant.wireName)
            .put("isStreaming", currentStreaming)
            .put("tokens", JSONObject(tokens))
            .put("renderId", renderId)
            .toString()

    AndroidView(
        modifier = modifier.height(visibleHeight),
        factory = { ctx ->
            val bridgeOwner = RenderedMarkdownBridgeOwner()
            val renderState = RenderedMarkdownWebViewState()
            WebView(ctx).apply {
                tag = RenderedMarkdownWebViewHolder(bridgeOwner, renderState)
                setBackgroundColor(Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                settings.javaScriptEnabled = true
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.domStorageEnabled = false
                settings.allowContentAccess = false
                settings.allowFileAccess = false
                settings.blockNetworkLoads = true
                addJavascriptInterface(
                    RenderedMarkdownBridge(bridgeOwner),
                    "OpenCodeRenderedMarkdownBridge",
                )
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean = true

                        override fun onPageFinished(
                            view: WebView,
                            url: String?,
                        ) {
                            renderState.markReady(view)
                        }
                    }
                loadDataWithBaseURL("https://opencode.local/rendered-markdown/", html, "text/html", "utf-8", null)
            }
        },
        onReset = { webView ->
            (webView.tag as? RenderedMarkdownWebViewHolder)?.let { holder ->
                holder.bridgeOwner.reset()
                holder.renderState.resetForReuse()
            }
        },
        onRelease = { webView ->
            (webView.tag as? RenderedMarkdownWebViewHolder)?.bridgeOwner?.reset()
            webView.removeJavascriptInterface("OpenCodeRenderedMarkdownBridge")
            webView.stopLoading()
            webView.destroy()
        },
        update = { webView ->
            val holder = webView.tag as? RenderedMarkdownWebViewHolder ?: return@AndroidView
            val nextRenderId = renderId()
            holder.bridgeOwner.activeRenderId = nextRenderId
            holder.bridgeOwner.onHeight = { height ->
                val nextHeight = maxOf(1f, height).dp
                if (abs(contentHeight.value - nextHeight.value) > 1f) {
                    contentHeight = nextHeight
                }
                currentContentHeightChange(nextHeight)
                currentExpandableChange(collapsedMaxHeight != null && nextHeight > collapsedMaxHeight)
            }
            holder.bridgeOwner.onLink = { url ->
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    uriHandler.openUri(url)
                }
            }
            holder.renderState.render(webView, payload(nextRenderId))
        },
    )

    LaunchedEffect(initialHeight) {
        if (initialHeight.value > 1f && abs(contentHeight.value - initialHeight.value) > 1f) {
            contentHeight = initialHeight
        }
    }

    LaunchedEffect(contentHeight, collapsedMaxHeight) {
        currentExpandableChange(collapsedMaxHeight != null && contentHeight > collapsedMaxHeight)
    }
}

fun prewarmOpenCodeRenderedMarkdown(context: Context) {
    RenderedMarkdownWebRuntime.prewarm(context)
}

private object RenderedMarkdownWebRuntime {
    private var cachedHtml: String? = null
    private var didPrewarm = false

    fun html(context: Context): String {
        cachedHtml?.let { return it }
        val loaded =
            context.assets
                .open("rendered-markdown/rendered-markdown.html")
                .bufferedReader()
                .use { it.readText() }
        cachedHtml = loaded
        return loaded
    }

    fun prewarm(context: Context) {
        html(context)
        if (didPrewarm) return
        didPrewarm = true
        Handler(Looper.getMainLooper()).post {
            runCatching {
                WebView(context).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.domStorageEnabled = false
                    settings.allowContentAccess = false
                    settings.allowFileAccess = false
                    settings.blockNetworkLoads = true
                    destroy()
                }
            }
        }
    }
}

private class RenderedMarkdownWebViewHolder(
    val bridgeOwner: RenderedMarkdownBridgeOwner,
    val renderState: RenderedMarkdownWebViewState,
)

private class RenderedMarkdownWebViewState {
    private var ready = false
    private var pendingPayload: String? = null
    private var lastPayload: String? = null

    fun render(
        webView: WebView,
        payload: String,
    ) {
        if (payload == lastPayload || payload == pendingPayload) return
        if (!ready) {
            pendingPayload = payload
            return
        }
        pendingPayload = null
        lastPayload = payload
        webView.evaluateJavascript("window.OpenCodeRenderedMarkdown.render($payload);", null)
    }

    fun markReady(webView: WebView) {
        ready = true
        val nextPayload = pendingPayload ?: return
        pendingPayload = null
        lastPayload = null
        render(webView, nextPayload)
    }

    fun resetForReuse() {
        pendingPayload = null
        lastPayload = null
    }
}

private class RenderedMarkdownBridgeOwner {
    var activeRenderId: String? = null
    var onHeight: (Float) -> Unit = {}
    var onLink: (String) -> Unit = {}

    fun reset() {
        activeRenderId = null
        onHeight = {}
        onLink = {}
    }
}

private class RenderedMarkdownBridge(
    private val owner: RenderedMarkdownBridgeOwner,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun postMessage(raw: String) {
        val message = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (message.optString("type")) {
            "height" -> {
                val height = message.optDouble("height", 1.0).toFloat()
                val renderId = message.optString("renderId")
                mainHandler.post {
                    if (renderId.isNotEmpty() && renderId == owner.activeRenderId) {
                        owner.onHeight(height)
                    }
                }
            }
            "link" -> {
                val url = message.optString("url")
                val renderId = message.optString("renderId")
                mainHandler.post {
                    if (renderId.isNotEmpty() && renderId == owner.activeRenderId) {
                        owner.onLink(url)
                    }
                }
            }
        }
    }
}
