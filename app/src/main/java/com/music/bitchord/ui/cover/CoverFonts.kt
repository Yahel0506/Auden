package com.music.bitchord.ui.cover

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import com.music.bitchord.R

/**
 * Where each [CoverFont] comes from: the app's own SF Pro Display in its five cuts, and the faces every Android
 * phone carries — Roboto and its condensed cut, Noto Serif, Droid Sans Mono, Cutive Mono, Dancing Script, Coming
 * Soon, Carrois Gothic SC — asked for by the family names the system knows them by.
 */
internal object CoverFonts {
    @Volatile private var pro: Array<Typeface?> = arrayOfNulls(5)

    fun init(context: Context) {
        if (pro[0] != null) return
        val ids = intArrayOf(
            R.font.sf_pro_display_regular, R.font.sf_pro_display_medium, R.font.sf_pro_display_semibold,
            R.font.sf_pro_display_bold, R.font.sf_pro_display_heavy,
        )
        pro = Array(5) { runCatching { ResourcesCompat.getFont(context, ids[it]) }.getOrNull() }
    }

    private fun family(font: CoverFont): String = when (font) {
        CoverFont.PRO, CoverFont.SANS -> "sans-serif"
        CoverFont.CONDENSED -> "sans-serif-condensed"
        CoverFont.SERIF -> "serif"
        CoverFont.MONO -> "monospace"
        CoverFont.TYPEWRITER -> "serif-monospace"
        CoverFont.SCRIPT -> "cursive"
        CoverFont.HANDWRITTEN -> "casual"
        CoverFont.SMALLCAPS -> "sans-serif-smallcaps"
    }

    fun typeface(font: CoverFont, weight: Int): Typeface {
        if (font == CoverFont.PRO) {
            val cut = when {
                weight <= 450 -> 0
                weight <= 550 -> 1
                weight <= 650 -> 2
                weight <= 750 -> 3
                else -> 4
            }
            pro[cut]?.let { return it }
        }
        val base = Typeface.create(family(font), Typeface.NORMAL)
        return if (Build.VERSION.SDK_INT >= 28) {
            Typeface.create(base, weight.coerceIn(1, 1000), false)
        } else {
            Typeface.create(base, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }
    }
}
