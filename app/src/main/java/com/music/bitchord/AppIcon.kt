package com.music.bitchord

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The home-screen icon choices. Each is an `<activity-alias>` in the manifest
 * carrying the launcher intent filter; exactly one is enabled at a time.
 *
 * Minimal is the default: the white A-waveform on black. The two Liquid Glass
 * icons are adaptive icons whose foreground already holds the finished icon in
 * its own Apple squircle, so [previewRes] — that foreground — shows the variant
 * as designed rather than through the device's launcher mask. All three keep the
 * alias names of the icons they replaced, so a choice made before the update
 * stays enabled.
 */
enum class AppIcon(val alias: String, val iconRes: Int, val labelRes: Int, val previewRes: Int? = null) {
    Default("LauncherDefault", R.mipmap.ic_launcher, R.string.app_icon_default),
    LiquidGlass("LauncherGlassLight", R.mipmap.ic_liquid_glass, R.string.app_icon_liquid_glass, R.mipmap.ic_liquid_glass_foreground),
    LiquidGlassDark("LauncherGlassDark", R.mipmap.ic_liquid_glass_dark, R.string.app_icon_liquid_glass_dark, R.mipmap.ic_liquid_glass_dark_foreground);

    private fun component(context: Context) =
        ComponentName(context.packageName, "com.music.bitchord.$alias")

    companion object {
        /** Retired variants of the old mark; still declared so no phone is left without a launcher entry. */
        private val legacyAliases = listOf(
            "LauncherGlassClear", "LauncherGlassClearDark",
            "LauncherSamsungGlass", "LauncherSamsungGlassDark",
            "LauncherSamsungGlassClear", "LauncherSamsungGlassClearDark",
            "LauncherPixelGlass", "LauncherPixelGlassDark",
            "LauncherPixelGlassClear", "LauncherPixelGlassClearDark",
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
