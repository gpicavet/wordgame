package app.wordgame.android.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import app.wordgame.android.BuildConfig
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import java.util.concurrent.atomic.AtomicBoolean

/** Built with -Pads=true: AdMob, with Google's consent form (UMP). */
fun createAds(context: Context): Ads = AdMob(context.applicationContext)

private const val TAG = "Ads"

private class AdMob(private val context: Context) : Ads {
    private val consent: ConsentInformation = UserMessagingPlatform.getConsentInformation(context)
    private val started = AtomicBoolean(false)
    private var rewarded: RewardedAd? = null
    private var loading = false

    override val enabled = true

    override fun start(activity: Activity) {
        consent.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                    if (error != null) Log.w(TAG, "Consent form: ${error.message}")
                    startSdk()
                }
            },
            { error -> Log.w(TAG, "Consent info: ${error.message}"); startSdk() },
        )
        // Consent given in an earlier session: no need to wait for the update.
        startSdk()
    }

    private fun startSdk() {
        if (!consent.canRequestAds() || !started.compareAndSet(false, true)) return
        MobileAds.initialize(context) { load() }
    }

    private fun load() {
        if (!consent.canRequestAds() || rewarded != null || loading) return
        loading = true
        RewardedAd.load(context, BuildConfig.ADMOB_REWARDED_ID, AdRequest.Builder().build(), object : RewardedAdLoadCallback() {
            override fun onAdLoaded(ad: RewardedAd) {
                rewarded = ad
                loading = false
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                Log.w(TAG, "Rewarded ad: ${error.message}")
                loading = false
            }
        })
    }

    override fun showRewarded(activity: Activity, onReward: () -> Unit, onUnavailable: () -> Unit) {
        val ad = rewarded
        if (ad == null) {
            load()
            onUnavailable()
            return
        }
        rewarded = null
        var earned = false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                load()
                if (earned) onReward()
            }

            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                load()
                onUnavailable()
            }
        }
        ad.show(activity) { earned = true }
    }

    override val privacyOptionsRequired: Boolean
        get() = consent.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    override fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error -> if (error != null) Log.w(TAG, "Privacy options: ${error.message}") }
    }
}
