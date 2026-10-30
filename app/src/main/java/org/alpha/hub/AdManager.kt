package org.alpha.hub

import android.app.Activity
import android.widget.FrameLayout
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

interface AdBridge {
    fun setBanner(visible: Boolean)
    fun showRewarded(done: (Boolean) -> Unit)
    fun showInterstitial(done: (Boolean) -> Unit)
}

object AdUnits {
    const val BANNER = "ca-app-pub-3940256099942544/6300978111"
    const val INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"
    const val REWARDED = "ca-app-pub-3940256099942544/5224354917"
}

class AdManager(private val act: Activity, private val container: FrameLayout, private val settings: SettingsStore) : AdBridge {
    private var banner: AdView? = null
    private var inter: InterstitialAd? = null
    private var rewarded: RewardedAd? = null
    private var wantBanner = true
    private var ready = false

    init {
        Thread {
            try {
                MobileAds.setRequestConfiguration(RequestConfiguration.Builder()
                    .setTagForChildDirectedTreatment(RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_FALSE).build())
                MobileAds.initialize(act) {
                    ready = true
                    act.runOnUiThread { makeBanner(); loadInter(); loadRewarded() }
                }
            } catch (_: Throwable) { }
        }.start()
    }

    private fun makeBanner() {
        try {
            val v = AdView(act)
            v.setAdSize(AdSize.BANNER)
            v.adUnitId = AdUnits.BANNER
            v.adListener = object : AdListener() { override fun onAdFailedToLoad(e: LoadAdError) { } }
            container.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER_HORIZONTAL))
            v.loadAd(AdRequest.Builder().build())
            banner = v
            apply()
        } catch (_: Throwable) { }
    }

    private fun apply() {
        val show = wantBanner && settings.getBool("online_mode") && banner != null
        container.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
    }

    override fun setBanner(visible: Boolean) { wantBanner = visible; act.runOnUiThread { apply() } }

    private fun loadInter() {
        try {
            InterstitialAd.load(act, AdUnits.INTERSTITIAL, AdRequest.Builder().build(), object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) { inter = ad }
                override fun onAdFailedToLoad(e: LoadAdError) { inter = null }
            })
        } catch (_: Throwable) { }
    }

    private fun loadRewarded() {
        try {
            RewardedAd.load(act, AdUnits.REWARDED, AdRequest.Builder().build(), object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) { rewarded = ad }
                override fun onAdFailedToLoad(e: LoadAdError) { rewarded = null }
            })
        } catch (_: Throwable) { }
    }

    override fun showInterstitial(done: (Boolean) -> Unit) {
        act.runOnUiThread {
            val ad = inter
            if (ad == null) { loadInter(); done(false); return@runOnUiThread }
            inter = null
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() { loadInter(); done(true) }
                override fun onAdFailedToShowFullScreenContent(e: AdError) { loadInter(); done(false) }
            }
            try { ad.show(act) } catch (_: Throwable) { done(false) }
        }
    }

    override fun showRewarded(done: (Boolean) -> Unit) {
        act.runOnUiThread {
            val ad = rewarded
            if (ad == null) { loadRewarded(); done(false); return@runOnUiThread }
            rewarded = null
            var earned = false
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() { loadRewarded(); done(earned) }
                override fun onAdFailedToShowFullScreenContent(e: AdError) { loadRewarded(); done(false) }
            }
            try { ad.show(act) { earned = true } } catch (_: Throwable) { done(false) }
        }
    }
}
