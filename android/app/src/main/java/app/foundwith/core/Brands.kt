package app.foundwith.core

/** Phone makers whose battery savers stop apps after a while, grouped by what the fix is called. */
enum class BrandFamily { XIAOMI, VIVO, OPPO, ONEPLUS, REALME, SAMSUNG, TRANSSION, HUAWEI, ASUS, OTHER }

/** One settings screen to try, by package and activity. Phones differ, so there are several per brand. */
data class SettingsScreen(val packageName: String, val className: String)

/** What the fix screen says for a brand. */
data class PhoneFix(val setting: String, val detail: String, val screens: List<SettingsScreen>)

/**
 * Like Truecaller's help pages per brand, inside the app. The settings screens below are the
 * ones these brands have used across versions; any that doesn't exist on a phone is skipped, and
 * the last resort is the app's own page in Android settings, which every phone has.
 */
object Brands {
    fun family(manufacturer: String?, brand: String? = null): BrandFamily {
        val m = "${manufacturer.orEmpty()} ${brand.orEmpty()}".lowercase()
        return when {
            "xiaomi" in m || "redmi" in m || "poco" in m -> BrandFamily.XIAOMI
            "vivo" in m || "iqoo" in m -> BrandFamily.VIVO
            "oneplus" in m -> BrandFamily.ONEPLUS
            "realme" in m -> BrandFamily.REALME
            "oppo" in m -> BrandFamily.OPPO
            "samsung" in m -> BrandFamily.SAMSUNG
            "tecno" in m || "infinix" in m || "itel" in m || "transsion" in m -> BrandFamily.TRANSSION
            "huawei" in m || "honor" in m -> BrandFamily.HUAWEI
            "asus" in m -> BrandFamily.ASUS
            else -> BrandFamily.OTHER
        }
    }

    /** Whether this brand is known to stop apps in the background unless the person allows it. */
    fun isStrict(family: BrandFamily): Boolean = family in setOf(
        BrandFamily.XIAOMI, BrandFamily.VIVO, BrandFamily.OPPO, BrandFamily.ONEPLUS, BrandFamily.REALME,
        BrandFamily.TRANSSION, BrandFamily.HUAWEI, BrandFamily.ASUS,
    )

    fun fix(family: BrandFamily): PhoneFix = when (family) {
        BrandFamily.XIAOMI -> PhoneFix("Turn on Autostart", "For Found With, in your phone's settings. Then set Battery saver to No restrictions.",
            listOf(SettingsScreen("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
        BrandFamily.VIVO -> PhoneFix("Turn on Autostart", "For Found With, in your phone's settings. Then allow High background power use.",
            listOf(SettingsScreen("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                SettingsScreen("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")))
        BrandFamily.OPPO, BrandFamily.REALME -> PhoneFix("Allow background activity", "For Found With, in your phone's settings: Auto launch, and battery set to Allow background activity.",
            listOf(SettingsScreen("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                SettingsScreen("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                SettingsScreen("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")))
        BrandFamily.ONEPLUS -> PhoneFix("Allow background activity", "For Found With, in your phone's settings: Auto launch, and battery set to Allow background activity.",
            listOf(SettingsScreen("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
                SettingsScreen("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")))
        BrandFamily.SAMSUNG -> PhoneFix("Turn off battery limits", "For Found With: set Battery to Unrestricted, and keep it out of Sleeping apps.",
            listOf(SettingsScreen("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")))
        BrandFamily.TRANSSION -> PhoneFix("Turn on Auto-start", "For Found With, in Phone Master. Then remove it from battery saving.",
            listOf(SettingsScreen("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity")))
        BrandFamily.HUAWEI -> PhoneFix("Manage app launch manually", "For Found With, turn on Auto-launch and Run in background.",
            listOf(SettingsScreen("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")))
        BrandFamily.ASUS -> PhoneFix("Turn on Auto-start", "For Found With, in Mobile Manager.",
            listOf(SettingsScreen("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity")))
        BrandFamily.OTHER -> PhoneFix("Turn off battery limits", "For Found With, set Battery to Unrestricted in your phone's settings.", emptyList())
    }

    /** The brand as reported in the health numbers: lower case, letters and digits only. */
    fun reportName(manufacturer: String?): String {
        val cleaned = manufacturer.orEmpty().lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim()
        return cleaned.take(40).ifEmpty { "unknown" }
    }
}
