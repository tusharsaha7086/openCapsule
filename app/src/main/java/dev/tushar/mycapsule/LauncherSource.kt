package dev.tushar.mycapsule

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.provider.Settings

/**
 * The apps the quick launcher starts with: the phone's own dialer, messages, browser, camera and so on, found by what they
 * do and not by name, so it fits any phone. Done once, the first time the card or its picker is used; after that the list
 * is the user's, and removing an app from it sticks.
 */
object LauncherDefaults {

    private val CATEGORIES = listOf(
        Intent.CATEGORY_APP_MESSAGING, Intent.CATEGORY_APP_BROWSER, Intent.CATEGORY_APP_MAPS, Intent.CATEGORY_APP_GALLERY,
        Intent.CATEGORY_APP_CALENDAR, Intent.CATEGORY_APP_CALCULATOR, Intent.CATEGORY_APP_EMAIL, Intent.CATEGORY_APP_MUSIC,
        Intent.CATEGORY_APP_CONTACTS,
    )

    fun seed(context: Context, prefs: Prefs) {
        if (prefs.launcherSeeded) return
        prefs.launcherSeeded = true
        if (prefs.launcherApps.isNotEmpty()) return
        prefs.launcherApps = defaults(context).take(Prefs.MAX_LAUNCHER_APPS)
    }

    private fun defaults(context: Context): List<String> {
        val packages = context.packageManager
        val actions = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
            Intent(Settings.ACTION_SETTINGS),
        ) + CATEGORIES.map { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, it) }
        return actions.mapNotNull { intent ->
            // An app chosen by the user as the default answers directly; when none is chosen Android answers with its own
            // picker ("android"), which is not an app to launch.
            packages.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }.filter { it != "android" && it != context.packageName && packages.getLaunchIntentForPackage(it) != null }.distinct()
    }
}

/**
 * The quick launcher card of the deck: the apps chosen under Dynamic cards, as icons in a grid. Tapping one opens that
 * app and closes the deck. An app that has been uninstalled since is left out.
 */
class LauncherSource : CapsuleSource {

    override fun start(context: Context, emit: (CapsuleEvent) -> Unit) = Unit

    override fun card(context: Context): CapsuleEvent {
        val packages = context.packageManager
        val prefs = Prefs(context)
        LauncherDefaults.seed(context, prefs)
        val tiles = prefs.launcherApps.mapNotNull { pkg ->
            runCatching {
                val intent = packages.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return@mapNotNull null
                Tile(
                    icon = packages.getApplicationIcon(pkg),
                    label = packages.getApplicationLabel(packages.getApplicationInfo(pkg, 0)).toString(),
                    on = false,
                    dismisses = true,
                    tinted = false,
                ) { runCatching { context.startActivity(intent) } }
            }.getOrNull()
        }
        return CapsuleEvent(
            kind = Kind.LAUNCHER,
            title = "Quick launcher",
            text = if (tiles.isEmpty()) "Choose your apps under Dynamic cards" else "Tap an app to open it",
            icon = context.getDrawable(R.drawable.ic_apps),
            tiles = tiles,
        )
    }
}
