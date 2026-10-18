package com.almog.spotifytablet.lyrics.bridge

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.almog.spotifytablet.lyrics.ui.LyricsView
import com.almog.spotifytablet.lyrics.viewmodel.LyricsViewModel

/**
 * Clean Java-to-Kotlin/Compose interop bridge for initializing and interacting with ComposeView.
 */
object LyricsComposeBridge {

    // Per-view "is this layout the visible one" flag. The home screen holds two lyrics views (old
    // and new style) and hides one with GONE, but a GONE ComposeView still composes, so the hidden
    // one converted/romanized/translated every song a second time. Inactive views compose nothing.
    private val active = java.util.WeakHashMap<ComposeView, androidx.compose.runtime.MutableState<Boolean>>()

    private fun flag(view: ComposeView) = active.getOrPut(view) { mutableStateOf(true) }

    @JvmStatic
    fun setActive(composeView: ComposeView?, isActive: Boolean) {
        if (composeView == null) return
        val f = flag(composeView)
        if (f.value != isActive) f.value = isActive
    }

    /**
     * Functional callback interface for Java interoperability on seek events.
     */
    fun interface OnNoLyricsListener {
        fun onNoLyricsChanged(noLyrics: Boolean)
    }

    /** Told when the song being shown turns out to have no lyrics (settled, not just loading), and when that ends. */
    @Volatile
    private var noLyricsListener: OnNoLyricsListener? = null

    @JvmStatic
    fun setNoLyricsListener(listener: OnNoLyricsListener?) {
        noLyricsListener = listener
    }

    fun interface OnSeekRequestedListener {
        fun onSeekRequested(positionMs: Long)
    }

    /**
     * Initializes the provided [ComposeView] with [LyricsView] content and proper lifecycle strategy.
     */
    @JvmStatic
    @JvmOverloads
    fun initLyricsView(
        composeView: ComposeView,
        viewModel: LyricsViewModel,
        onSeekListener: OnSeekRequestedListener? = null
    ) {
        com.almog.spotifytablet.lyrics.mobile.MobileLyricsSources.init(composeView.context)
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        val isActive = flag(composeView)
        composeView.setContent {
            if (isActive.value) {
                val ui by viewModel.uiState.collectAsStateWithLifecycle()
                val noLyrics = !ui.isLoading && (ui.track == null || ui.track?.lines.isNullOrEmpty())
                LaunchedEffect(noLyrics) { noLyricsListener?.onNoLyricsChanged(noLyrics) }
                LyricsView(
                    viewModel = viewModel,
                    onLineClicked = { pos ->
                        onSeekListener?.onSeekRequested(pos)
                    }
                )
            }
        }
    }
}
