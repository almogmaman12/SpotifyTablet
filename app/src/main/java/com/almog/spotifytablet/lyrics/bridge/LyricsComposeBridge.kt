package com.almog.spotifytablet.lyrics.bridge

import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.almog.spotifytablet.lyrics.ui.LyricsView
import com.almog.spotifytablet.lyrics.viewmodel.LyricsViewModel

/**
 * Clean Java-to-Kotlin/Compose interop bridge for initializing and interacting with ComposeView.
 */
object LyricsComposeBridge {

    /**
     * Functional callback interface for Java interoperability on seek events.
     */
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
        composeView.setContent {
            LyricsView(
                viewModel = viewModel,
                onLineClicked = { pos ->
                    onSeekListener?.onSeekRequested(pos)
                }
            )
        }
    }
}
