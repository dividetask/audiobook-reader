package com.dividetask.audiobookreader.player

import androidx.media3.common.Player

/**
 * The real (unwrapped) player, set by [PlaybackService]. The app reads positions from
 * it because the session's player reports chapter-relative values for the lock
 * screen (see [ChapterProgressPlayer]). Main thread only.
 */
object PlayerHolder {
    var player: Player? = null
}
