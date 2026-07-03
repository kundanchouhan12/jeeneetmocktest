package com.jeeneet.mocktest.admob

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.jeeneet.mocktest.data.model.AdUnitIds
import com.jeeneet.mocktest.utils.PrefManager

object AdManager {

    private const val TAG = "AdManager"
    private const val MAX_RETRIES = 3

    private var interstitialAd: InterstitialAd? = null
    private var rewardedAd: RewardedAd? = null
    private var isInterstitialLoading = false
    private var isRewardedLoading = false
    private var isRewardedShowing = false  // guard against rapid-tap double-show

    // Queued callbacks — invoked automatically once the ad finishes loading
    private var pendingInterstitialCallback: (() -> Unit)? = null
    private var pendingRewardedOnRewarded: (() -> Unit)? = null
    private var pendingRewardedOnNotAvailable: (() -> Unit)? = null

    private var rewardedRetryCount = 0
    private var interstitialRetryCount = 0
    private var lastInterstitialShownMs = 0L
    private const val INTERSTITIAL_COOLDOWN_MS = 30_000L // 30 seconds
    private const val REWARDED_QUEUE_TIMEOUT_MS = 8_000L

    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var interstitialRetryRunnable: Runnable? = null
    private var rewardedRetryRunnable: Runnable? = null
    private var rewardedTimeoutRunnable: Runnable? = null
    private var nativeAdRetryRunnable: Runnable? = null
    private var displayedNativeAd: NativeAd? = null  // currently shown in container
    private var preloadedNativeAd: NativeAd? = null  // loaded ahead-of-time, not yet shown
    private var lastNativeLoadMs = 0L
    private const val NATIVE_REFRESH_MS = 30 * 60 * 1000L // stale after 30 min
    private var isNativeAdLoading = false
    // Video ads fill a larger slot and typically earn higher eCPM than static images.
    // LANDSCAPE aspect ratio matches our 160dp tall media slot well.
    private val nativeAdOptions: NativeAdOptions = NativeAdOptions.Builder()
        .setMediaAspectRatio(NativeAdOptions.NATIVE_MEDIA_ASPECT_RATIO_LANDSCAPE)
        .setVideoOptions(
            com.google.android.gms.ads.VideoOptions.Builder()
                .setStartMuted(true)   // required by most stores / avoids user surprise
                .setClickToExpandRequested(true)
                .build()
        )
        .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
        .build()

    // ─── Initialization ───────────────────────────────────────────────────────

    fun init(context: Context, onReady: () -> Unit = {}) {
        val configBuilder = RequestConfiguration.Builder()
            .setTagForChildDirectedTreatment(RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_FALSE)
            // Education app — teen audience (JEE/NEET, 16-25 yrs). T opens more inventory than PG.
            .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_T)
            // Explicitly enable personalized ads — higher eCPM than non-personalized
            .setPublisherPrivacyPersonalizationState(
                RequestConfiguration.PublisherPrivacyPersonalizationState.ENABLED
            )

        // Register emulator as test device in debug so no invalid traffic is recorded
        if (com.jeeneet.mocktest.BuildConfig.DEBUG) {
            // To get your device's test ID: run the app, check Logcat for:
            // "Use RequestConfiguration.Builder().setTestDeviceIds(Arrays.asList("XXXXXXXX")) ..."
            // Then add your ID to this list. Until then, only the emulator is tagged as test traffic.
            configBuilder.setTestDeviceIds(listOf(
                AdRequest.DEVICE_ID_EMULATOR
            ))
        }

        MobileAds.setRequestConfiguration(configBuilder.build())
        try {
            MobileAds.initialize(context) { status ->
                status.adapterStatusMap.forEach { (adapter, adapterStatus) ->
                    Log.d(TAG, "Adapter: ${adapter.substringAfterLast('.')} → ${adapterStatus.initializationState} (${adapterStatus.description})")
                }
                Log.d(TAG, "AdMob initialized")
                // Preload the first set of full-screen ads for the session
                if (context is Activity) {
                    loadInterstitial(context)
                    loadRewarded(context)
                }
                onReady()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MobileAds: ${e.message}")
            onReady()
        }
    }

    // ─── Banner ───────────────────────────────────────────────────────────────

    fun showBanner(activity: Activity, container: FrameLayout) {
        if (PrefManager.isAdsRemoved(activity)) { container.removeAllViews(); return }
        loadBannerWithRetry(activity, container, retryCount = 0)
    }

    private fun loadBannerWithRetry(activity: Activity, container: FrameLayout, retryCount: Int) {
        val adSize = getAdSize(activity)
        val adView = AdView(activity).apply {
            setAdSize(adSize)
            adUnitId = AdUnitIds.BANNER
        }

        adView.adListener = object : AdListener() {
            override fun onAdLoaded() {
                container.removeAllViews()
                container.addView(adView)
                container.visibility = View.VISIBLE
                Log.d(TAG, "Banner load success")
            }
            override fun onAdFailedToLoad(e: LoadAdError) {
                Log.w(TAG, "Banner load failed (attempt ${retryCount + 1}): ${e.message}")
                if (retryCount < MAX_RETRIES) {
                    retryHandler.postDelayed({
                        if (!activity.isFinishing && !activity.isDestroyed)
                            loadBannerWithRetry(activity, container, retryCount + 1)
                    }, 3000L * (retryCount + 1))
                } else {
                    container.visibility = View.GONE
                }
            }
        }
        adView.loadAd(buildRequest())
    }

    private fun getAdSize(activity: Activity): AdSize {
        val display = activity.windowManager.defaultDisplay
        val outMetrics = android.util.DisplayMetrics()
        display.getMetrics(outMetrics)
        val density = outMetrics.density
        var adWidthPixels = outMetrics.widthPixels.toFloat()
        if (adWidthPixels == 0f) {
            adWidthPixels = outMetrics.widthPixels.toFloat()
        }
        val adWidth = (adWidthPixels / density).toInt()
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, adWidth)
    }



    // ─── Interstitial ─────────────────────────────────────────────────────────

    fun loadInterstitial(activity: Activity) {
        if (isInterstitialLoading || interstitialAd != null) return
        if (PrefManager.isAdsRemoved(activity)) return
        isInterstitialLoading = true

        try {
            InterstitialAd.load(
                activity, AdUnitIds.INTERSTITIAL, buildRequest(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        ad.onPaidEventListener = OnPaidEventListener { adValue ->
                            Log.d(TAG, "[Revenue] Interstitial: ${adValue.valueMicros}µ ${adValue.currencyCode} prec=${adValue.precisionType}")
                            com.jeeneet.mocktest.utils.AnalyticsManager.adImpression(
                                activity, "interstitial", AdUnitIds.INTERSTITIAL,
                                adValue.valueMicros, adValue.currencyCode, adValue.precisionType
                            )
                        }
                        interstitialAd = ad
                        isInterstitialLoading = false
                        interstitialRetryCount = 0
                        interstitialRetryRunnable?.let { retryHandler.removeCallbacks(it) }
                        interstitialRetryRunnable = null
                        Log.d(TAG, "Interstitial loaded")
                        // Fire any queued show
                        pendingInterstitialCallback?.let { cb ->
                            pendingInterstitialCallback = null
                            showInterstitial(activity, onDismissed = cb)
                        }
                    }
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        interstitialAd = null
                        isInterstitialLoading = false
                        Log.w(TAG, "Interstitial failed (attempt ${interstitialRetryCount + 1}): ${error.message}")
                        if (interstitialRetryCount < MAX_RETRIES) {
                            interstitialRetryCount++
                            interstitialRetryRunnable?.let { retryHandler.removeCallbacks(it) }
                            interstitialRetryRunnable = Runnable {
                                if (!activity.isFinishing && !activity.isDestroyed)
                                    loadInterstitial(activity)
                            }
                            retryHandler.postDelayed(interstitialRetryRunnable!!, 3000L * interstitialRetryCount)
                        } else {
                            // Give up — fire the pending callback without an ad
                            interstitialRetryCount = 0
                            pendingInterstitialCallback?.invoke()
                            pendingInterstitialCallback = null
                        }
                    }
                }
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in loadInterstitial: ${e.message}")
            isInterstitialLoading = false
        } catch (e: Exception) {
            Log.e(TAG, "Error in loadInterstitial: ${e.message}")
            isInterstitialLoading = false
        }
    }

    /**
     * Show interstitial immediately if ready, or queue it to show once loaded.
     * [onDismissed] is always called — with or without the ad.
     * Set [bypassCooldown] = true to always show regardless of the frequency cap.
     */
    fun showInterstitial(activity: Activity, bypassCooldown: Boolean = false, onDismissed: () -> Unit = {}) {
        if (PrefManager.isAdsRemoved(activity)) { onDismissed(); return }

        // Frequency cap: max 1 interstitial per 5 minutes (skipped when bypassCooldown = true)
        val now = System.currentTimeMillis()
        if (!bypassCooldown && now - lastInterstitialShownMs < INTERSTITIAL_COOLDOWN_MS) {
            onDismissed()
            return
        }

        val ad = interstitialAd
        if (ad == null) {
            // Queue and trigger a load — will auto-show when ready
            pendingInterstitialCallback = onDismissed
            loadInterstitial(activity)
            return
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                lastInterstitialShownMs = System.currentTimeMillis()
                interstitialAd = null
                interstitialRetryCount = 0
                loadInterstitial(activity)
                onDismissed()
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                interstitialAd = null
                onDismissed()
            }
        }
        ad.show(activity)
    }

    fun isInterstitialReady(): Boolean = interstitialAd != null

    // ─── Rewarded ─────────────────────────────────────────────────────────────

    fun loadRewarded(activity: Activity) {
        if (isRewardedLoading || rewardedAd != null) return
        if (PrefManager.isAdsRemoved(activity)) return
        isRewardedLoading = true

        try {
            RewardedAd.load(
                activity, AdUnitIds.REWARDED, buildRequest(),
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        ad.onPaidEventListener = OnPaidEventListener { adValue ->
                            Log.d(TAG, "[Revenue] Rewarded: ${adValue.valueMicros}µ ${adValue.currencyCode} prec=${adValue.precisionType}")
                            com.jeeneet.mocktest.utils.AnalyticsManager.adImpression(
                                activity, "rewarded", AdUnitIds.REWARDED,
                                adValue.valueMicros, adValue.currencyCode, adValue.precisionType
                            )
                        }
                        rewardedAd = ad
                        isRewardedLoading = false
                        rewardedRetryCount = 0
                        rewardedRetryRunnable?.let { retryHandler.removeCallbacks(it) }
                        rewardedRetryRunnable = null
                        rewardedTimeoutRunnable?.let { retryHandler.removeCallbacks(it) }
                        rewardedTimeoutRunnable = null
                        Log.d(TAG, "Rewarded loaded")
                        // Fire any queued show
                        val onRewarded = pendingRewardedOnRewarded
                        val onNotAvailable = pendingRewardedOnNotAvailable
                        if (onRewarded != null) {
                            pendingRewardedOnRewarded = null
                            pendingRewardedOnNotAvailable = null
                            showRewarded(activity, onRewarded, onNotAvailable ?: {})
                        }
                    }
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        rewardedAd = null
                        isRewardedLoading = false
                        Log.w(TAG, "Rewarded failed (attempt ${rewardedRetryCount + 1}): ${error.message}")
                        if (rewardedRetryCount < MAX_RETRIES) {
                            rewardedRetryCount++
                            rewardedRetryRunnable?.let { retryHandler.removeCallbacks(it) }
                            rewardedRetryRunnable = Runnable {
                                if (!activity.isFinishing && !activity.isDestroyed)
                                    loadRewarded(activity)
                            }
                            retryHandler.postDelayed(rewardedRetryRunnable!!, 3000L * rewardedRetryCount)
                        } else {
                            // Give up — fire not-available callback
                            rewardedRetryCount = 0
                            pendingRewardedOnNotAvailable?.invoke()
                            pendingRewardedOnRewarded = null
                            pendingRewardedOnNotAvailable = null
                        }
                    }
                }
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in loadRewarded: ${e.message}")
            isRewardedLoading = false
        } catch (e: Exception) {
            Log.e(TAG, "Error in loadRewarded: ${e.message}")
            isRewardedLoading = false
        }
    }

    /**
     * Show rewarded ad immediately if ready, or queue it to show once loaded.
     * Shows a loading state to the user via [onLoading] while waiting.
     */
    fun showRewarded(
        activity: Activity,
        onRewarded: () -> Unit,
        onNotAvailable: () -> Unit = {},
        onLoading: (() -> Unit)? = null,
        placement: String = "unknown"
    ) {
        if (PrefManager.isAdsRemoved(activity)) { onRewarded(); return }
        if (isRewardedShowing) return  // rapid-tap guard — ad already on screen

        val ad = rewardedAd
        if (ad == null) {
            pendingRewardedOnRewarded = onRewarded
            pendingRewardedOnNotAvailable = onNotAvailable
            onLoading?.invoke()
            loadRewarded(activity)
            // Fallback: if ad doesn't load within 8s, fire onNotAvailable so user isn't stuck
            rewardedTimeoutRunnable?.let { retryHandler.removeCallbacks(it) }
            rewardedTimeoutRunnable = Runnable {
                val cb = pendingRewardedOnNotAvailable
                if (cb != null) {
                    pendingRewardedOnRewarded = null
                    pendingRewardedOnNotAvailable = null
                    cb.invoke()
                }
            }
            retryHandler.postDelayed(rewardedTimeoutRunnable!!, REWARDED_QUEUE_TIMEOUT_MS)
            return
        }

        // Track reward separately so onRewarded() fires AFTER ad dismisses,
        // not during — prevents starting a new Activity mid-ad which causes early close.
        var earned = false

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                isRewardedShowing = true
                com.jeeneet.mocktest.utils.AnalyticsManager.rewardedAdShown(activity, placement)
            }
            override fun onAdDismissedFullScreenContent() {
                isRewardedShowing = false
                rewardedAd = null
                rewardedRetryCount = 0
                loadRewarded(activity) // pre-load next
                if (earned) {
                    com.jeeneet.mocktest.utils.AnalyticsManager.rewardedAdCompleted(activity, placement)
                    onRewarded()
                } else {
                    com.jeeneet.mocktest.utils.AnalyticsManager.rewardedAdDismissed(activity, placement)
                }
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                isRewardedShowing = false
                rewardedAd = null
                onNotAvailable()
            }
        }
        ad.show(activity) { rewardItem ->
            Log.d(TAG, "User earned reward: ${rewardItem.amount} ${rewardItem.type}")
            earned = true  // fires BEFORE onAdDismissed — reward delivered only there
        }
    }

    fun isRewardedReady() = rewardedAd != null
    fun isRewardedLoading() = isRewardedLoading

    // ─── Native Ad ────────────────────────────────────────────────────────────

    /**
     * Silently preloads a native ad into cache. Call right after AdManager.init() (e.g. from
     * Application.onCreate) so the first home-screen render is instant with zero blank time.
     */
    fun preloadNativeAd(context: Context) {
        if (AdUnitIds.IS_DEBUG) return
        if (PrefManager.isAdsRemoved(context)) return
        if (isNativeAdLoading) return
        // Cache already fresh — skip redundant network hit
        if (preloadedNativeAd != null &&
            System.currentTimeMillis() - lastNativeLoadMs < NATIVE_REFRESH_MS) return

        try {
            isNativeAdLoading = true
            AdLoader.Builder(context.applicationContext, AdUnitIds.NATIVE)
                .forNativeAd { nativeAd ->
                    isNativeAdLoading = false
                    preloadedNativeAd?.destroy()
                    preloadedNativeAd = nativeAd
                    lastNativeLoadMs = System.currentTimeMillis()
                    Log.d(TAG, "Native preloaded")
                }
                .withAdListener(object : AdListener() {
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        isNativeAdLoading = false
                        Log.w(TAG, "Native preload failed: ${error.message}")
                    }
                })
                .withNativeAdOptions(nativeAdOptions)
                .build()
                .loadAd(buildRequest())
        } catch (e: Exception) {
            Log.e(TAG, "Error in preloadNativeAd: ${e.message}")
            isNativeAdLoading = false
        }

    }

    /**
     * Attaches a native ad to [container]:
     * — Instant render if a fresh preloaded ad is cached (< [NATIVE_REFRESH_MS]).
     * — Skips re-render if the container already shows a fresh ad (survives buildContent() rebuilds).
     * — Triggers a background preload after every render so the next call is always instant.
     * — Hides the container if all retries exhaust (no fallback ads — prevents policy violations).
     * Safe to call on every buildContent() / onResume — internal guards prevent double-loads.
     */
    fun loadNativeAd(context: Context, container: FrameLayout, retryCount: Int = 0) {
        if (AdUnitIds.IS_DEBUG) { container.visibility = View.GONE; return }
        if (PrefManager.isAdsRemoved(context)) { container.visibility = View.GONE; return }

        try {
            val now = System.currentTimeMillis()
            val isStale = now - lastNativeLoadMs > NATIVE_REFRESH_MS

            // Container already has a fresh rendered ad — nothing to do
            if (container.childCount > 0 && !isStale) return

            // Fresh preloaded ad available — render instantly, then warm up next slot
            val cached = preloadedNativeAd
            if (cached != null && !isStale) {
                preloadedNativeAd = null          // consume from cache
                renderNativeAd(context, container, cached)
                preloadNativeAd(context)          // immediately warm up next
                return
            }

            // Need a network load (cache empty or stale)
            if (isNativeAdLoading && retryCount == 0) return
            isNativeAdLoading = true

            AdLoader.Builder(context, AdUnitIds.NATIVE)
                .forNativeAd { nativeAd ->
                    isNativeAdLoading = false
                    lastNativeLoadMs = System.currentTimeMillis()
                    renderNativeAd(context, container, nativeAd)
                }
                .withAdListener(object : AdListener() {
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        isNativeAdLoading = false
                        Log.w(TAG, "Native failed (attempt ${retryCount + 1}): ${error.message}")
                        if (retryCount < MAX_RETRIES) {
                            nativeAdRetryRunnable?.let { retryHandler.removeCallbacks(it) }
                            nativeAdRetryRunnable = Runnable {
                                val act = context as? Activity
                                if (act == null || (!act.isFinishing && !act.isDestroyed))
                                    loadNativeAd(context, container, retryCount + 1)
                            }
                            retryHandler.postDelayed(nativeAdRetryRunnable!!, 10_000L * (retryCount + 1))
                        } else {
                            container.visibility = View.GONE
                        }
                    }
                })
                .withNativeAdOptions(nativeAdOptions)
                .build()
                .loadAd(buildRequest())
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in loadNativeAd: ${e.message}")
            isNativeAdLoading = false
            container.visibility = View.GONE
        } catch (e: Exception) {
            Log.e(TAG, "Error in loadNativeAd: ${e.message}")
            isNativeAdLoading = false
            container.visibility = View.GONE
        }
    }

    private fun renderNativeAd(context: Context, container: FrameLayout, nativeAd: NativeAd) {
        // Revenue tracking — compare per-format to find highest earner
        nativeAd.setOnPaidEventListener { adValue ->
            Log.d(TAG, "[Revenue] Native: ${adValue.valueMicros}µ ${adValue.currencyCode} prec=${adValue.precisionType}")
            com.jeeneet.mocktest.utils.AnalyticsManager.adImpression(
                context, "native", AdUnitIds.NATIVE,
                adValue.valueMicros, adValue.currencyCode, adValue.precisionType
            )
        }
        displayedNativeAd?.destroy()
        displayedNativeAd = nativeAd
        container.removeAllViews()
        container.addView(buildNativeAdView(context, nativeAd))
        container.visibility = View.VISIBLE
    }

    fun cleanup() {
        interstitialRetryRunnable?.let { retryHandler.removeCallbacks(it) }
        rewardedRetryRunnable?.let  { retryHandler.removeCallbacks(it) }
        nativeAdRetryRunnable?.let  { retryHandler.removeCallbacks(it) }
        rewardedTimeoutRunnable?.let { retryHandler.removeCallbacks(it) }
        interstitialRetryRunnable = null
        rewardedRetryRunnable     = null
        nativeAdRetryRunnable     = null
        rewardedTimeoutRunnable   = null
        
        // We only destroy ads that are NOT shared/preloaded or that are definitively 
        // no longer needed. preloadedAds are kept for the next activity.

        displayedNativeAd?.destroy()
        preloadedNativeAd?.destroy()
        displayedNativeAd = null
        preloadedNativeAd = null
        lastNativeLoadMs  = 0L
        interstitialAd    = null
        rewardedAd        = null
        isInterstitialLoading = false
        isRewardedLoading     = false
        isRewardedShowing     = false
        isNativeAdLoading     = false
        pendingInterstitialCallback   = null
        pendingRewardedOnRewarded     = null
        pendingRewardedOnNotAvailable = null
    }

    private fun buildNativeAdView(context: Context, nativeAd: NativeAd): NativeAdView {
        val dp = context.resources.displayMetrics.density
        val bgCard      = Color.parseColor("#0E1829")
        val gold        = Color.parseColor("#F59E0B")
        val goldDark    = Color.parseColor("#080E1A")
        val textPrimary = Color.WHITE
        val textMuted   = Color.parseColor("#94A3B8")

        val nativeAdView = NativeAdView(context)

        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(bgCard)
                cornerRadius = 20 * dp
                setStroke((1 * dp).toInt(), Color.parseColor("#40F59E0B"))
            }
        }

        // ── Header: [Ad badge]  [Icon 44dp]  [Headline + Advertiser] ──────────
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvAdBadge = TextView(context).apply {
            text = "Ad"; textSize = 9f
            setTextColor(goldDark)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(gold); cornerRadius = 4 * dp
            }
            setPadding((5 * dp).toInt(), (2 * dp).toInt(), (5 * dp).toInt(), (2 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = (10 * dp).toInt() }
        }

        val iconView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = 10 * dp
                setColor(Color.parseColor("#1A2540"))
            }
            layoutParams = LinearLayout.LayoutParams(
                (44 * dp).toInt(), (44 * dp).toInt()
            ).also { it.marginEnd = (12 * dp).toInt() }
        }
        nativeAd.icon?.drawable?.let { iconView.setImageDrawable(it) }
        nativeAdView.iconView = iconView

        // Headline + advertiser stacked
        val textStack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvHeadline = TextView(context).apply {
            text = nativeAd.headline ?: ""
            textSize = 16f                               // bigger headline → higher CTR
            setTextColor(textPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        nativeAdView.headlineView = tvHeadline
        textStack.addView(tvHeadline)

        nativeAd.advertiser?.let { advertiser ->
            val tvAdvertiser = TextView(context).apply {
                text = advertiser; textSize = 11f
                setTextColor(textMuted)
                setPadding(0, (3 * dp).toInt(), 0, 0)
            }
            nativeAdView.advertiserView = tvAdvertiser
            textStack.addView(tvAdvertiser)
        }

        headerRow.addView(tvAdBadge)
        headerRow.addView(iconView)
        headerRow.addView(textStack)
        outer.addView(headerRow)

        // ── MediaView — shows image or auto-plays video; 3× eCPM vs text-only ──
        val mediaView = MediaView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (160 * dp).toInt()
            ).also { it.topMargin = (10 * dp).toInt(); it.bottomMargin = (4 * dp).toInt() }
            setMediaContent(nativeAd.mediaContent)
        }
        nativeAdView.mediaView = mediaView
        outer.addView(mediaView)

        // ── Body text ──────────────────────────────────────────────────────────
        nativeAd.body?.let { body ->
            val tvBody = TextView(context).apply {
                text = body; textSize = 12f
                setTextColor(textMuted)
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = (6 * dp).toInt(); it.bottomMargin = (4 * dp).toInt() }
            }
            nativeAdView.bodyView = tvBody
            outer.addView(tvBody)
        }

        // ── Star rating — shown for app/game advertisers, improves CTR ──────────
        nativeAd.starRating?.let { rating ->
            val tvRating = TextView(context).apply {
                text = "★ ${"%.1f".format(rating)}"
                textSize = 12f
                setTextColor(gold)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = (4 * dp).toInt() }
            }
            nativeAdView.starRatingView = tvRating
            outer.addView(tvRating)
        }

        // ── CTA — full-width, tall button, easy to tap ─────────────────────────
        nativeAd.callToAction?.let { cta ->
            val tvCta = TextView(context).apply {
                text = cta; textSize = 14f
                setTextColor(goldDark)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(gold); cornerRadius = 24 * dp
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (44 * dp).toInt()
                ).also { it.topMargin = (10 * dp).toInt() }
            }
            nativeAdView.callToActionView = tvCta
            outer.addView(tvCta)
        }

        nativeAdView.addView(outer)
        nativeAdView.setNativeAd(nativeAd)
        return nativeAdView
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    internal fun buildRequest() = AdRequest.Builder()
        // Neighboring URLs give AdMob content signals for better contextual matching → higher eCPM.
        // Only include real crawlable URLs — fake/unreachable URLs suppress fill rate.
        .setNeighboringContentUrls(listOf(
            "https://jeemains.nta.nic.in",
            "https://neet.nta.nic.in",
            "https://ncert.nic.in"
        ))
        .build()
}
