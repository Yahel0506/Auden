package com.music.bitchord.ui.components

import androidx.annotation.StringRes
import com.music.bitchord.R
import dev.auden.library.Service

/** The name a service goes by on screen. */
@StringRes
fun serviceLabelRes(service: Service): Int = when (service) {
    Service.SPOTIFY -> R.string.spotify
    Service.YT_MUSIC -> R.string.transfer_yt_music
    Service.APPLE_MUSIC -> R.string.apple_music
    Service.TIDAL -> R.string.tidal
}
