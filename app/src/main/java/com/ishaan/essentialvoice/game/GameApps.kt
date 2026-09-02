package com.ishaan.essentialvoice.game

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Which packages are games, and which apps the picker offers.
 *
 * The answer to the first is the store's, not this app's:
 * [ApplicationInfo.CATEGORY_GAME] is set by whoever published the app, so a
 * heuristic on the name is neither needed nor as good. It is used once, to seed
 * the list; after that the list is the user's and this is not consulted again.
 *
 * The manifest carries a `<queries>` element for the launcher intent rather than
 * QUERY_ALL_PACKAGES. That is the difference between seeing the apps somebody
 * could plausibly play and being able to enumerate everything on their phone,
 * and the first is all this needs.
 */
object GameApps {

    /** One row in the picker. */
    data class Entry(val packageName: String, val label: String, val isGame: Boolean)

    /**
     * Everything launchable, games first, each sorted by name.
     *
     * Games first because that is the list somebody came here to tick, and the
     * rest of the phone is underneath it for the two or three the store labelled
     * wrongly — an emulator, or a game that shipped as an "entertainment" app.
     */
    fun launchable(context: Context): List<Entry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        val out = ArrayList<Entry>()
        runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList()).forEach { ri ->
            val info = ri.activityInfo?.applicationInfo ?: return@forEach
            if (info.packageName == context.packageName) return@forEach
            if (!seen.add(info.packageName)) return@forEach
            out += Entry(
                packageName = info.packageName,
                label = runCatching { pm.getApplicationLabel(info).toString() }
                    .getOrDefault(info.packageName),
                isGame = info.category == ApplicationInfo.CATEGORY_GAME,
            )
        }
        return out.sortedWith(compareByDescending<Entry> { it.isGame }.thenBy { it.label.lowercase() })
    }

    /** The seed for the auto-arm list: whatever the store calls a game. */
    fun declaredGames(context: Context): Set<String> =
        launchable(context).filter { it.isGame }.map { it.packageName }.toSet()

    fun label(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    /**
     * Packages that are never a game, whatever is in front.
     *
     * The shade, the keyboard and the launcher all raise a window over a running
     * game without the game having been left, and treating any of them as
     * "something else is in front now" would disarm game mode every time the
     * notification shade was pulled down.
     */
    fun isTransient(context: Context, packageName: String): Boolean =
        packageName == context.packageName ||
            packageName == "com.android.systemui" ||
            packageName.startsWith("com.android.inputmethod") ||
            packageName.endsWith(".inputmethod.latin")
}
