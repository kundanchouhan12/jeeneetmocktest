package com.jeeneet.mocktest.ui.home

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jeeneet.mocktest.admob.IAPManager
import com.jeeneet.mocktest.data.model.IAPProducts
import com.jeeneet.mocktest.ui.style.*
import com.jeeneet.mocktest.utils.AnalyticsManager
import com.jeeneet.mocktest.utils.PrefManager

data class ShopItem(
    val productId: String,
    val title: String,
    val description: String,
    val price: String,
    val isBestValue: Boolean = false
)

class ShopActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) =
            context.startActivity(Intent(context, ShopActivity::class.java))
    }

    private lateinit var iapManager: IAPManager
    private lateinit var shopContainer: LinearLayout

    private val shopItems = listOf(
        ShopItem(IAPProducts.REMOVE_ADS, "Remove All Ads",
            "Enjoy the app completely ad-free. One-time purchase.", "₹99"),
        ShopItem(IAPProducts.JEE_PHYSICS_PACK, "JEE Physics Pack",
            "200+ chapter-wise questions covering all JEE Physics topics.", "₹49"),
        ShopItem(IAPProducts.JEE_CHEM_PACK, "JEE Chemistry Pack",
            "200+ questions: Organic, Inorganic & Physical Chemistry.", "₹49"),
        ShopItem(IAPProducts.JEE_MATHS_PACK, "JEE Maths Pack",
            "200+ questions: Calculus, Algebra, Coordinate Geometry.", "₹49"),
        ShopItem(IAPProducts.NEET_BIO_PACK, "NEET Biology Pack",
            "300+ questions: Botany, Zoology, Human Physiology.", "₹49"),
        ShopItem(IAPProducts.NEET_CHEM_PACK, "NEET Chemistry Pack",
            "200+ questions aligned to NEET Chemistry syllabus.", "₹49"),
        ShopItem(IAPProducts.NEET_PHYSICS_PACK, "NEET Physics Pack",
            "200+ questions covering the full NEET Physics syllabus.", "₹49"),
        ShopItem(IAPProducts.ALL_ACCESS_YEARLY, "All Access — Annual",
            "✔ No Ads\n✔ All Subjects Unlocked\n✔ Unlimited Tests\n✔ Detailed Solutions\n✔ Priority Updates", "₹149/year",
            isBestValue = true)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        iapManager = IAPManager(this)
        iapManager.onRestoreComplete = { runOnUiThread { refreshShopCards() } }
        iapManager.connect()
        iapManager.onPurchaseSuccess = { productId ->
            AnalyticsManager.purchaseSuccess(this, productId)
            // Note: IAPManager.grantEntitlement already calls syncPack() for pack products.
            // No need to duplicate that call here — just refresh UI and notify the user.
            runOnUiThread {
                Toast.makeText(this, "Purchase successful! Content unlocked.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
        iapManager.onPurchaseFailed = { code ->
            runOnUiThread {
                Toast.makeText(this, "Purchase failed (code $code). Please try again.", Toast.LENGTH_SHORT).show()
            }
        }
        setContentView(buildLayout())
    }

    override fun onDestroy() {
        super.onDestroy()
        iapManager.disconnect()
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(bgPrimary)
        }

        root.addView(uiHeader("👑 Premium Content", onBack = { finish() }))

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        shopContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Space.L.dp, Space.XL.dp, Space.L.dp, Space.XXL.dp)
        }

        populateShopContainer()
        scroll.addView(shopContainer)
        root.addView(scroll)
        return root
    }

    private fun refreshShopCards() {
        shopContainer.removeAllViews()
        populateShopContainer()
    }

    private fun populateShopContainer() {
        shopContainer.addView(uiTextView(
            UiText.BODY,
            "Unlock more questions and go ad-free.\nOne-time purchase — yours forever.",
            textTertiary
        ).apply {
            lineHeight = (22 * resources.displayMetrics.density).toInt()
            setPadding(0, 0, 0, Space.XL.dp)
        })

        shopItems.forEach { shopContainer.addView(buildShopCard(it)) }

        shopContainer.addView(uiTextView(
            UiText.CAPTION,
            "Previously purchased on this Google account? Items are restored automatically.",
            textMuted,
            Gravity.CENTER
        ).apply {
            setPadding(0, Space.S.dp, 0, Space.XS.dp)
        })
    }

    private fun buildShopCard(item: ShopItem): View {
        val isOwned = when (item.productId) {
            IAPProducts.REMOVE_ADS       -> PrefManager.isAdsRemoved(this)
            IAPProducts.ALL_ACCESS_YEARLY -> PrefManager.isAllAccessUnlocked(this)
            else                          -> PrefManager.isPackUnlocked(this, item.productId)
        }

        val card = uiCard(
            radius = Corner.L,
            elevation = if (item.isBestValue) Elev.L else Elev.M,
            background = bgSecondary,
            strokeDp = if (item.isBestValue) 2 else 0,
            strokeColor = goldPrimary
        ).apply {
            layoutParams = lpRow(bottomDp = 14)
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp, 18.dp, 18.dp, 18.dp)
        }

        if (item.isBestValue) {
            inner.addView(TextView(this).apply {
                text = "🔥 MOST POPULAR"
                textSize = 11f
                setTextColor(goldPrimary)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                letterSpacing = 0.1f
                background = roundedFill(Color.parseColor("#1AF59E0B"), Corner.M)
                setPadding(10.dp, Space.XS.dp, 10.dp, Space.XS.dp)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 10.dp }
            })
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(uiTextView(UiText.H3, item.title, textPrimary).apply {
            textSize = 17f
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        titleRow.addView(uiTextView(
            UiText.H3,
            if (isOwned) "Owned" else item.price,
            if (isOwned) Color.parseColor("#22C55E") else goldLight
        ).apply {
            textSize = 16f
        })
        inner.addView(titleRow)

        inner.addView(uiTextView(UiText.LABEL, item.description, textTertiary).apply {
            lineHeight = (20 * resources.displayMetrics.density).toInt()
            setPadding(0, 6.dp, 0, 14.dp)
        })

        if (!isOwned) {
            inner.addView(uiPrimaryButton("Buy ${item.price}", heightDp = 46) {
                if (PrefManager.isGuestMode(this@ShopActivity) || com.google.firebase.auth.FirebaseAuth.getInstance().currentUser == null) {
                    showGuestPurchaseDialog()
                    return@uiPrimaryButton
                }
                if (PrefManager.isPackUnlocked(this@ShopActivity, item.productId) ||
                    (item.productId == IAPProducts.REMOVE_ADS && PrefManager.isAdsRemoved(this@ShopActivity)) ||
                    (item.productId == IAPProducts.ALL_ACCESS_YEARLY && PrefManager.isAllAccessUnlocked(this@ShopActivity))
                ) {
                    Toast.makeText(this@ShopActivity, "Already purchased", Toast.LENGTH_SHORT).show()
                    refreshShopCards()
                    return@uiPrimaryButton
                }
                if (!com.jeeneet.mocktest.utils.NetworkUtils.isNetworkAvailable(this@ShopActivity)) {
                    showNoInternetToast()
                    return@uiPrimaryButton
                }
                AnalyticsManager.purchaseClicked(this@ShopActivity, item.productId)
                iapManager.purchase(this@ShopActivity, item.productId)
            }.apply { textSize = 14f; stateListAnimator = null })
        } else {
            inner.addView(uiTextView(UiText.BODY, "✓ Unlocked", Color.parseColor("#22C55E")).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
        }

        card.addView(inner)
        return card
    }

    private fun showGuestPurchaseDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("👑 Login Required to Purchase")
            .setMessage(
                "Please login or create an account before purchasing.\n\n" +
                "This ensures your purchases and unlocked packs remain permanently saved to your account and accessible across all your devices."
            )
            .setPositiveButton("⚡ Login / Sign Up") { _, _ ->
                startActivity(Intent(this, com.jeeneet.mocktest.ui.auth.LoginActivity::class.java))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
