package com.infinitum.aether.ads

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Optional AdMob banner. Ads stay off until [BuildConfig.ADS_ENABLED] is flipped
 * to true (release builds ship with it false). When off, consent is refused, or
 * a banner fails to load, [slot] is GONE and takes no space, so existing screens
 * do not change.
 *
 * Debug builds use Google's test banner unit; release builds use the real unit
 * (still only if ADS_ENABLED is true). UMP consent is requested before any ad.
 */
object Ads {
    fun attach(activity: Activity, slot: FrameLayout) {
        slot.visibility = View.GONE
        slot.layoutParams = slot.layoutParams?.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        if (!com.infinitum.aether.BuildConfig.ADS_ENABLED) return

        val params = ConsentRequestParameters.Builder().build()
        val info = UserMessagingPlatform.getConsentInformation(activity)
        info.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    if (info.canRequestAds()) showBanner(activity, slot)
                }
            },
            { /* leave the slot collapsed */ },
        )
    }

    private fun showBanner(activity: Activity, slot: FrameLayout) {
        MobileAds.initialize(activity) {
            activity.runOnUiThread {
                val ad = AdView(activity)
                ad.setAdSize(AdSize.BANNER)
                ad.adUnitId = com.infinitum.aether.BuildConfig.ADMOB_BANNER_ID
                ad.adListener = object : AdListener() {
                    override fun onAdLoaded() {
                        slot.visibility = View.VISIBLE
                        applyBottomInset(slot)
                    }
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        slot.visibility = View.GONE
                        slot.setPadding(0, 0, 0, 0)
                    }
                }
                slot.removeAllViews()
                slot.addView(
                    ad,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                ad.loadAd(AdRequest.Builder().build())
            }
        }
    }

    private fun applyBottomInset(slot: FrameLayout) {
        val insets = ViewCompat.getRootWindowInsets(slot) ?: return
        val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        slot.setPadding(0, 0, 0, bottom)
    }
}
