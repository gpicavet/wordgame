package app.wordgame.android.ads

import android.app.Activity
import android.content.Context
import java.time.LocalDate

/**
 * Advertising, compiled in only with -Pads=true (app/src/ads); the default build (app/src/noads) has no ad SDK at
 * all, so no advertising ID permission and nothing to declare. Ads only ever appear when the player asks for
 * them: a short video unlocks the hints of a turn, past [HintQuota.FREE_PER_DAY] free turns a day.
 */
interface Ads {
    /** Ads are compiled in and configured. */
    val enabled: Boolean

    /** At startup: asks for consent where the law requires it (EEA, UK…), then starts the ad SDK. */
    fun start(activity: Activity)

    /** Shows a rewarded video; [onReward] once watched, [onUnavailable] when there is none to show (or no consent). */
    fun showRewarded(activity: Activity, onReward: () -> Unit, onUnavailable: () -> Unit)

    /** The player must be able to change their consent (a button in "My account") where it was asked. */
    val privacyOptionsRequired: Boolean

    fun showPrivacyOptions(activity: Activity)
}

/** Free turns with hints per day, then a video per turn. Counted on the phone, by game and turn. */
class HintQuota(context: Context, private val today: () -> LocalDate = LocalDate::now) {
    private val prefs = context.getSharedPreferences("hint-quota", Context.MODE_PRIVATE)

    private fun turns(): Set<String> =
        if (prefs.getString("day", null) == today().toString()) prefs.getStringSet("turns", emptySet())!! else emptySet()

    /** Hints of this turn are free: already unlocked, or some free turns left today. */
    fun isFree(gameId: String, moveNumber: Int): Boolean {
        val turns = turns()
        return "$gameId:$moveNumber" in turns || turns.size < FREE_PER_DAY
    }

    /** The hints of this turn were shown (free or after a video): reopening them costs nothing more. */
    fun use(gameId: String, moveNumber: Int) {
        prefs.edit().putString("day", today().toString()).putStringSet("turns", turns() + "$gameId:$moveNumber").apply()
    }

    companion object {
        const val FREE_PER_DAY = 3
    }
}

/** The activity behind a composable's context (to show consent forms and videos). */
fun Context.findActivity(): Activity? {
    var c = this
    while (c is android.content.ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
