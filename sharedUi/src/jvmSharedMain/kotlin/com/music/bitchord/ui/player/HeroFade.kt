package com.music.bitchord.ui.player

/**
 * The dissolve at the bottom of the full-bleed banner, where the cover gives way to the backdrop.
 *
 * One curve for every banner — the still sleeve and the motion cover's video layer — so the two
 * never differ. It is eased at both ends (a smootherstep, whose slope is zero where it starts and
 * where it ends) rather than a straight ramp: a linear fade has a visible corner where it begins and
 * another where it reaches nothing, and those two lines are what read as a seam. Eased, the
 * cover thins out slowly, spends its middle going quickly and settles into the backdrop with no edge
 * to find.
 */
object HeroFade {
    /** Share of the banner's height the dissolve takes, from its bottom edge upwards. */
    const val FRACTION = 0.62f

    private const val STEPS = 16

    /** Positions of the stops along the dissolve, 0 at its top and 1 at the banner's bottom edge. */
    val positions: FloatArray = FloatArray(STEPS + 1) { it / STEPS.toFloat() }

    /** How much of the cover is left at each of [positions]: 1, falling to 0. */
    val alphas: FloatArray = FloatArray(STEPS + 1) { i ->
        val t = i / STEPS.toFloat()
        1f - t * t * t * (t * (t * 6f - 15f) + 10f)
    }
}
