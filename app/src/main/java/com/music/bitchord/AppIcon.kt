package com.music.bitchord

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The home-screen icon choices. Each is an `<activity-alias>` in the manifest
 * carrying the launcher intent filter; exactly one is enabled at a time.
 *
 * Liquid Glass Dark is the default. The two Liquid Glass icons are adaptive icons whose foreground already holds
 * the finished icon in its own squircle, so [previewRes] — that foreground — shows the variant as designed rather
 * than through the device's launcher mask. The colour icons are plain adaptive icons, filled edge to edge. Every one
 * carries the same silhouette as its monochrome layer, for Android's themed icons. The alias names of the icons
 * that came before stay valid: a choice made before the update stays enabled, and `LauncherGlassDark` (now the
 * default) moves to it.
 */
enum class AppIcon(val alias: String, val iconRes: Int, val labelRes: Int, val previewRes: Int? = null) {
    Default("LauncherDefault", R.mipmap.ic_launcher, R.string.app_icon_default, R.mipmap.ic_liquid_glass_dark_foreground),
    LiquidGlass("LauncherGlassLight", R.mipmap.ic_liquid_glass, R.string.app_icon_liquid_glass, R.mipmap.ic_liquid_glass_foreground),
    Black("LauncherBlack", R.mipmap.ic_icon_black, R.string.app_icon_black),
    White("LauncherWhite", R.mipmap.ic_icon_white, R.string.app_icon_white),
    Blue("LauncherBlue", R.mipmap.ic_icon_blue, R.string.app_icon_blue),
    Green("LauncherGreen", R.mipmap.ic_icon_green, R.string.app_icon_green),
    Lime("LauncherLime", R.mipmap.ic_icon_lime, R.string.app_icon_lime),
    Yellow("LauncherYellow", R.mipmap.ic_icon_yellow, R.string.app_icon_yellow),
    Orange("LauncherOrange", R.mipmap.ic_icon_orange, R.string.app_icon_orange),
    Red("LauncherRed", R.mipmap.ic_icon_red, R.string.app_icon_red),
    Pink("LauncherPink", R.mipmap.ic_icon_pink, R.string.app_icon_pink),
    Magenta("LauncherMagenta", R.mipmap.ic_icon_magenta, R.string.app_icon_magenta),
    Violet("LauncherViolet", R.mipmap.ic_icon_violet, R.string.app_icon_violet);

    private fun component(context: Context) =
        ComponentName(context.packageName, "com.music.bitchord.$alias")

    companion object {
        /** Retired variants of the old mark; still declared so no phone is left without a launcher entry. */
        private val legacyAliases = listOf(
            // The dark glass icon is the default now.
            "LauncherGlassDark",
            "LauncherGlassClear", "LauncherGlassClearDark",
            "LauncherSamsungGlass", "LauncherSamsungGlassDark",
            "LauncherSamsungGlassClear", "LauncherSamsungGlassClearDark",
            "LauncherPixelGlass", "LauncherPixelGlassDark",
            "LauncherPixelGlassClear", "LauncherPixelGlassClearDark",
            // The old BitChord mark, retired with it.
            "LauncherMonochrome", "LauncherNeonSilver", "LauncherNeonCyan", "LauncherNeonViolet", "LauncherSamsungNeonSilver", "LauncherSamsungNeonCyan", "LauncherSamsungNeonViolet", "LauncherPixelNeonSilver", "LauncherPixelNeonCyan", "LauncherPixelNeonViolet",
        )

        private fun legacyComponent(context: Context, alias: String) =
            ComponentName(context.packageName, "com.music.bitchord.$alias")

        /** A phone that still has a retired variant enabled moves to the default icon. */
        fun migrateLegacy(context: Context) {
            val pm = context.packageManager
            val onLegacy = legacyAliases.any {
                pm.getComponentEnabledSetting(legacyComponent(context, it)) ==
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            if (onLegacy) apply(context, Default)
        }

        fun current(context: Context): AppIcon {
            val pm = context.packageManager
            return entries.firstOrNull {
                pm.getComponentEnabledSetting(it.component(context)) ==
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } ?: Default
        }

        /** Enables [icon] before disabling the rest so there is never a moment with no launcher entry. */
        fun apply(context: Context, icon: AppIcon) {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                icon.component(context),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            val others = entries.filter { it != icon }.map { it.component(context) } +
                legacyAliases.map { legacyComponent(context, it) }
            others.forEach {
                pm.setComponentEnabledSetting(
                    it,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }
}
