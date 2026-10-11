package com.almog.spotifytablet.lyrics.web

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.almog.spotifytablet.Constants
import com.almog.spotifytablet.lyrics.ui.LyricsAttributionBadge
import com.almog.spotifytablet.lyrics.viewmodel.LyricsUiState

private const val SPICY_PAGE_URL = "file:///android_asset/spicy/index.html"

/**
 * Lyrics rendered by the bundled Spicy Lyrics page (assets/spicy). The page owns layout, animation
 * and scrolling; this composable only feeds it the track and the playback clock and relays taps.
 *
 * [onUnavailable] is called if a WebView cannot be created, so the caller can fall back to the
 * native renderer.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpicyWebLyricsContent(
    uiState: LyricsUiState,
    modifier: Modifier = Modifier,
    onLineClicked: ((Long) -> Unit)? = null,
    onUserScrollStateChanged: ((Boolean) -> Unit)? = null,
    onUnavailable: () -> Unit = {}
) {
    val context = LocalContext.current
    val track = uiState.track
    val anchor = uiState.anchor

    val currentOnLineClicked by rememberUpdatedState(onLineClicked)
    val currentOnUserScroll by rememberUpdatedState(onUserScrollStateChanged)

    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }

    // Spicy sizes its lyrics from the pane width; only override that if the user picked a size.
    val fontSizeSp = remember(context) {
        val prefs = context.getSharedPreferences(Constants.PREF_NAME, android.content.Context.MODE_PRIVATE)
        if (prefs.contains(Constants.PREF_KEY_LYRICS_FONT_SIZE)) prefs.getInt(Constants.PREF_KEY_LYRICS_FONT_SIZE, 0) else 0
    }

    // Track -> page. Quoting the JSON as a JS string keeps it a single, safely escaped argument.
    LaunchedEffect(webView, pageReady, track) {
        val view = webView ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        val json = if (track == null || track.lines.isEmpty()) "{\"type\":\"Line\",\"lines\":[]}" else SpicyLyricsJson.toJson(track)
        view.evaluateJavascript("SpicyLyrics.setLyrics(${SpicyLyricsJson.quote(json)})") { Log.d("SpicyWeb", "setLyrics returned $it, jsonLen=${json.length}") }
        view.evaluateJavascript("SpicyLyrics.setFontSize($fontSizeSp)", null) // 0 = Spicy's own default
    }

    // Playback clock -> page. The page extrapolates from this while playing, so it only needs updates
    // when the anchor changes (seek, pause/resume, periodic resync).
    LaunchedEffect(webView, pageReady, anchor.positionMs, anchor.anchorRealtimeMs, anchor.isPlaying, anchor.speed) {
        val view = webView ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        val elapsed = SystemClock.elapsedRealtime() - anchor.anchorRealtimeMs
        val now = if (anchor.isPlaying) anchor.positionMs + (elapsed * anchor.speed).toLong() else anchor.positionMs
        view.evaluateJavascript("SpicyLyrics.setAnchor($now,${anchor.isPlaying},${anchor.speed})", null)
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView<android.view.View>(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val view = try {
                    WebView(ctx)
                } catch (t: Throwable) {
                    onUnavailable()
                    return@AndroidView android.view.View(ctx)
                }
                view.setBackgroundColor(AndroidColor.TRANSPARENT)
                view.isVerticalScrollBarEnabled = false
                view.isHorizontalScrollBarEnabled = false
                view.overScrollMode = android.view.View.OVER_SCROLL_NEVER
                view.settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = true
                    allowContentAccess = false
                    setSupportZoom(false)
                }
                view.addJavascriptInterface(
                    SpicyJsBridge(
                        seekCallback = { positionMs -> currentOnLineClicked?.invoke(positionMs) },
                        scrollCallback = { scrolling -> currentOnUserScroll?.invoke(scrolling) }
                    ),
                    "Android"
                )
                view.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                        Log.d("SpicyWeb", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                        return true
                    }
                }
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView?, url: String?) {
                        Log.d("SpicyWeb", "page finished: $url size=${v?.width}x${v?.height}")
                        pageReady = true
                    }
                }
                view.loadUrl(SPICY_PAGE_URL)
                view
            },
            update = { view ->
                if (view is WebView && webView !== view) webView = view
            },
            onRelease = { view ->
                if (view is WebView) {
                    view.removeJavascriptInterface("Android")
                    view.destroy()
                }
                webView = null
                pageReady = false
            }
        )

        track?.attribution?.let { attr ->
            LyricsAttributionBadge(
                attr = attr,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 28.dp, bottom = 12.dp)
            )
        }
    }
}

/**
 * Methods the page calls through `window.Android`. They arrive on a WebView thread, so they are
 * posted to the main thread before touching Compose state.
 */
class SpicyJsBridge(
    private val seekCallback: (Long) -> Unit,
    private val scrollCallback: (Boolean) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onSeek(positionMs: Double) {
        main.post { seekCallback.invoke(positionMs.toLong()) }
    }

    @JavascriptInterface
    fun onUserScroll(scrolling: Boolean) {
        main.post { scrollCallback.invoke(scrolling) }
    }
}
