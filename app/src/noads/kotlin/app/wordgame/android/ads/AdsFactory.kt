package app.wordgame.android.ads

import android.app.Activity
import android.content.Context

/** The default build: no ads, hints are always free. */
fun createAds(@Suppress("UNUSED_PARAMETER") context: Context): Ads = object : Ads {
    override val enabled = false
    override fun start(activity: Activity) {}
    override fun showRewarded(activity: Activity, onReward: () -> Unit, onUnavailable: () -> Unit) = onReward()
    override val privacyOptionsRequired = false
    override fun showPrivacyOptions(activity: Activity) {}
}
