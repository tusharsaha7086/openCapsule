package dev.tushar.mycapsule

import android.content.Context
import android.content.Intent

/**
 * The quick launcher card of the deck: the apps chosen under Dynamic cards, as icons in a grid. Tapping one opens that
 * app and closes the deck. An app that has been uninstalled since is left out.
 */
class LauncherSource : CapsuleSource {

    override fun start(context: Context, emit: (CapsuleEvent) -> Unit) = Unit

    override fun card(context: Context): CapsuleEvent {
        val packages = context.packageManager
        val tiles = Prefs(context).launcherApps.mapNotNull { pkg ->
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
