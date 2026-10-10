package com.almog.spotifytablet.lyrics.mobile

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

/** The original app hides its playback controls while lyrics are touched; this app has no such overlay. */
@Suppress("UNUSED_PARAMETER")
fun Modifier.keepsControlsHidden(tapsOnly: Boolean = false, hit: (Offset) -> Boolean = { true }): Modifier = this
