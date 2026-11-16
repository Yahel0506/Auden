package com.music.bitchord.data

/** Where a heart goes once an account is signed in; see [com.music.bitchord.data.settings.AppSettings.likeSync]. */
enum class LikeSync {
    /** Not chosen yet: hearts go to the account as they always have, and the likes kept on the device are offered to it. */
    ASK,

    /** To the account, and kept on the device too, so signing out loses nothing. */
    ACCOUNT,

    /** To the device only: the account's own likes are left as they are, and shown beside these. */
    LOCAL,
}
