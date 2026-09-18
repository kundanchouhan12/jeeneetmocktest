package com.jeeneet.mocktest.ui.doubts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jeeneet.mocktest.R
import com.jeeneet.mocktest.admob.AdManager
import com.jeeneet.mocktest.data.repository.CacheStatus
import com.jeeneet.mocktest.data.repository.GeminiRepository
import com.jeeneet.mocktest.ui.home.ShopActivity
import com.jeeneet.mocktest.ui.style.*
import com.jeeneet.mocktest.utils.AnalyticsManager
import com.jeeneet.mocktest.utils.PrefManager
import kotlinx.coroutines.launch
import java.io.File

class ScanActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var cameraLayout: FrameLayout
    private lateinit var reviewLayout: FrameLayout
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var reviewImageView: ImageView
    private lateinit var analyzeBtn: TextView
    private lateinit var tvScanCounter: TextView
    private lateinit var tvLoadingDetail: TextView

    private lateinit var textLayout: View
    private lateinit var etQuestion: EditText
    private lateinit var solveTextBtn: TextView
    private lateinit var galleryBtn: TextView
    private lateinit var tabScan: TextView
    private lateinit var tabAsk: TextView
    private var isTextMode   = false
    private var isProcessing = false

    private var imageCapture: ImageCapture? = null
    private var capturedBitmap: Bitmap? = null

    // Colors come from UiStyle as Context extensions.
    // Historical local `textMuted` was mapped to R.color.text_tertiary — usages renamed to `textTertiary`.
    private val purple = Color.parseColor("#8B5CF6")

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadBitmapFromUri(it) }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else {
            Toast.makeText(this, "Camera permission is required to scan questions", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        AdManager.loadRewarded(this)
        checkCameraPermission()
    }

    // ─── Layout ───────────────────────────────────────────────────────────────

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        cameraLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        cameraLayout.addView(previewView)
        cameraLayout.addView(buildCameraControls())

        reviewLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        reviewImageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        reviewLayout.addView(reviewImageView)
        reviewLayout.addView(buildReviewControls())

        loadingOverlay = buildLoadingOverlay()

        textLayout = buildTextLayout()

        root.addView(cameraLayout)
        root.addView(reviewLayout)
        root.addView(textLayout)
        root.addView(loadingOverlay)
        root.addView(buildTopBar())

        return root
    }

    private fun buildTopBar(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp, 44.dp, 16.dp, 10.dp)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            )
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(Color.parseColor("#EE000000"), Color.TRANSPARENT)
            }

            // ── Row 1: back + tabs + gallery ──
            val row = LinearLayout(this@ScanActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            row.addView(TextView(this@ScanActivity).apply {
                text = "←"; textSize = 22f; setTextColor(textPrimary)
                setPadding(12.dp, 8.dp, 16.dp, 8.dp)
                setOnClickListener { finish() }
            })

            // Tab container
            val tabContainer = LinearLayout(this@ScanActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                background = GradientDrawable().apply {
                    cornerRadius = 24.dpF
                    setColor(Color.parseColor("#33FFFFFF"))
                }
                setPadding(3.dp, 3.dp, 3.dp, 3.dp)
            }
            tabScan = TextView(this@ScanActivity).apply {
                text = "📷  Scan"; textSize = 13f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(18.dp, 8.dp, 18.dp, 8.dp)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { if (isTextMode) switchToScanMode() }
            }
            tabAsk = TextView(this@ScanActivity).apply {
                text = "✏️  Ask"; textSize = 13f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(18.dp, 8.dp, 18.dp, 8.dp)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { if (!isTextMode) switchToTextMode() }
            }
            tabContainer.addView(tabScan)
            tabContainer.addView(tabAsk)
            row.addView(tabContainer)

            galleryBtn = TextView(this@ScanActivity).apply {
                text = "🖼"; textSize = 18f; setTextColor(goldPrimary)
                setPadding(14.dp, 8.dp, 4.dp, 8.dp)
                setOnClickListener { galleryLauncher.launch("image/*") }
            }
            row.addView(galleryBtn)
            addView(row)

            // ── Row 2: scan counter ──
            tvScanCounter = TextView(this@ScanActivity).apply {
                textSize = 11f; setTextColor(textTertiary)
                setPadding(16.dp, 4.dp, 0, 0)
            }
            addView(tvScanCounter)
            updateScanCounter()
            updateTabStyle()
        }
    }

    private fun buildTextLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgPrimary)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(20.dp, 120.dp, 20.dp, 32.dp)
            visibility = View.GONE
        }

        root.addView(TextView(this).apply {
            text = "Type or paste your question"
            textSize = 16f; setTextColor(textPrimary)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(2.dp, 0, 0, 6.dp)
        })
        root.addView(TextView(this).apply {
            text = "Best for text questions · Use Scan tab for diagrams"
            textSize = 12f; setTextColor(textTertiary)
            setPadding(2.dp, 0, 0, 16.dp)
        })

        etQuestion = EditText(this).apply {
            hint = "e.g. A particle moves with velocity 20 m/s at angle 30°…"
            setHintTextColor(Color.parseColor("#55FFFFFF"))
            setTextColor(textPrimary)
            textSize = 14f
            gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 6; maxLines = 14
            background = GradientDrawable().apply {
                setColor(bgSecondary)
                cornerRadius = 14.dpF
                setStroke(1.dp, Color.parseColor("#30FFFFFF"))
            }
            setPadding(16.dp, 14.dp, 16.dp, 14.dp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 20.dp }
        }
        root.addView(etQuestion)

        solveTextBtn = TextView(this).apply {
            text = "✨  Solve with AI"
            textSize = 16f; setTextColor(Color.parseColor("#1A2540"))
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = 28.dpF
                colors = intArrayOf(goldPrimary, goldDark)
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            setPadding(0, 16.dp, 0, 16.dp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onSolveTextTapped() }
        }
        root.addView(solveTextBtn)
        return root
    }

    private fun switchToScanMode() {
        isTextMode = false
        textLayout.visibility = View.GONE
        if (capturedBitmap != null) {
            reviewLayout.visibility = View.VISIBLE
            cameraLayout.visibility = View.GONE
        } else {
            cameraLayout.visibility = View.VISIBLE
            reviewLayout.visibility = View.GONE
        }
        galleryBtn.visibility = View.VISIBLE
        updateTabStyle()
    }

    private fun switchToTextMode() {
        isTextMode = true
        cameraLayout.visibility = View.GONE
        reviewLayout.visibility = View.GONE
        textLayout.visibility = View.VISIBLE
        galleryBtn.visibility = View.GONE
        updateTabStyle()
        // Show keyboard
        etQuestion.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(etQuestion, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun updateTabStyle() {
        if (!::tabScan.isInitialized) return
        val activeColor  = Color.parseColor("#1A2540")
        val inactiveColor = textPrimary
        tabScan.setTextColor(if (!isTextMode) activeColor  else inactiveColor)
        tabAsk.setTextColor( if (isTextMode)  activeColor  else inactiveColor)
        tabScan.background = if (!isTextMode) GradientDrawable().apply {
            cornerRadius = 22.dpF
            colors = intArrayOf(goldPrimary, goldDark)
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
        } else null
        tabAsk.background = if (isTextMode) GradientDrawable().apply {
            cornerRadius = 22.dpF
            colors = intArrayOf(goldPrimary, goldDark)
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
        } else null
    }

    private fun onSolveTextTapped() {
        if (!com.jeeneet.mocktest.utils.NetworkUtils.isNetworkAvailable(this)) {
            showNoInternetToast()
            return
        }
        val question = etQuestion.text.toString().trim()
        if (question.length < 10) {
            Toast.makeText(this, "Please enter a question first", Toast.LENGTH_SHORT).show()
            return
        }
        if (com.jeeneet.mocktest.BuildConfig.GEMINI_API_KEY.isBlank()) {
            Toast.makeText(this, "Set your Gemini API key in local.properties", Toast.LENGTH_LONG).show()
            return
        }
        if (!PrefManager.canScanNow(this)) {
            AnalyticsManager.scanLimitReached(this)
            showScanLimitDialog()
            return
        }
        // Hide keyboard
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(etQuestion.windowToken, 0)

        loadingOverlay.visibility = View.VISIBLE
        tvLoadingDetail.text = "Solving with Gemini AI · Please wait"
        solveTextBtn.isEnabled = false
        AnalyticsManager.scanStarted(this)

        lifecycleScope.launch {
            GeminiRepository(this@ScanActivity).solveTextQuestion(question) { status ->
                runOnUiThread { tvLoadingDetail.text = status }
            }
                .onSuccess { result ->
                    if (result.cacheStatus != CacheStatus.HIT) {
                        PrefManager.incrementScanCount(this@ScanActivity)
                        com.jeeneet.mocktest.data.repository.ScanRepository(this@ScanActivity).recordScan()
                    }
                    AnalyticsManager.scanCompleted(this@ScanActivity, result.cacheStatus == CacheStatus.HIT)
                    loadingOverlay.visibility = View.GONE
                    updateScanCounter()
                    solveTextBtn.isEnabled = true
                    AISolutionActivity.start(this@ScanActivity, result.markdown)
                }
                .onFailure { error ->
                    loadingOverlay.visibility = View.GONE
                    solveTextBtn.isEnabled = true
                    val msg = error.message ?: "Something went wrong. Please try again."
                    val isRateLimit = msg.contains("rate", ignoreCase = true) ||
                                     msg.contains("wait", ignoreCase = true)
                    MaterialAlertDialogBuilder(this@ScanActivity)
                        .setTitle(if (isRateLimit) "⏳ AI Busy" else "Failed")
                        .setMessage(msg)
                        .setPositiveButton("Try Again") { _, _ -> onSolveTextTapped() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
        }
    }

    private fun buildCameraControls(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(TextView(this).apply {
            text = "Point camera at the question"
            textSize = 13f; setTextColor(Color.parseColor("#AAFFFFFF"))
            gravity = Gravity.CENTER; setPadding(0, 0, 0, 20.dp)
        })

        // Capture button
        val captureBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(80.dp, 80.dp).also { it.bottomMargin = 48.dp }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(4.dp, Color.parseColor("#80FFFFFF"))
            }
            setOnClickListener { capturePhoto() }
        }
        captureBtn.addView(View(this).apply {
            layoutParams = FrameLayout.LayoutParams(64.dp, 64.dp, Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(goldPrimary, goldDark)
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
            }
        })
        container.addView(captureBtn)
        return container
    }

    private fun buildReviewControls(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(24.dp, 16.dp, 24.dp, 48.dp)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.BOTTOM_TOP
                colors = intArrayOf(Color.parseColor("#EE000000"), Color.TRANSPARENT)
            }

            addView(TextView(this@ScanActivity).apply {
                text = "↺  Retake"; textSize = 15f; setTextColor(textPrimary)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = 28.dpF
                    setColor(Color.parseColor("#33FFFFFF"))
                    setStroke(1.dp, Color.parseColor("#55FFFFFF"))
                }
                setPadding(28.dp, 14.dp, 28.dp, 14.dp)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = 16.dp }
                setOnClickListener { showCameraMode() }
            })

            analyzeBtn = TextView(this@ScanActivity).apply {
                textSize = 15f
                setTextColor(Color.parseColor("#1A2540"))
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                background = GradientDrawable().apply {
                    cornerRadius = 28.dpF
                    colors = intArrayOf(goldPrimary, goldDark)
                    orientation = GradientDrawable.Orientation.LEFT_RIGHT
                    gradientType = GradientDrawable.LINEAR_GRADIENT
                }
                setPadding(32.dp, 14.dp, 32.dp, 14.dp)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { onAnalyzeTapped() }
            }
            updateAnalyzeBtnText()
            addView(analyzeBtn)
        }
    }

    private fun buildLoadingOverlay(): FrameLayout {
        return FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#EE080E1A"))
            visibility = View.GONE

            val inner = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
                setPadding(32.dp, 0, 32.dp, 0)
            }

            // Pulsing brain emoji
            inner.addView(TextView(context).apply {
                text = "🧠"; textSize = 52f; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 20.dp }
            })
            inner.addView(TextView(context).apply {
                text = "Solving your question…"
                textSize = 18f; setTextColor(textPrimary)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 8.dp }
            })
            tvLoadingDetail = TextView(context).apply {
                text = "Analyzing with Gemini AI · Please wait"
                textSize = 13f; setTextColor(textTertiary); gravity = Gravity.CENTER
            }
            inner.addView(tvLoadingDetail)

            inner.addView(ProgressBar(context).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(goldPrimary)
                layoutParams = LinearLayout.LayoutParams(48.dp, 48.dp, ).also { it.topMargin = 24.dp }
            })
            addView(inner)
        }
    }

    // ─── Camera ───────────────────────────────────────────────────────────────

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        val outFile = File(cacheDir, "scan_${System.currentTimeMillis()}.jpg")
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(outFile).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    decodeSafeBitmap(outFile.absolutePath)
                        ?.let {
                            AnalyticsManager.doubtImageCaptured(this@ScanActivity, "camera")
                            showReviewMode(it)
                        }
                        ?: Toast.makeText(this@ScanActivity, "Failed to load captured image", Toast.LENGTH_SHORT).show()
                }
                override fun onError(e: ImageCaptureException) {
                    Toast.makeText(this@ScanActivity, "Capture failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun loadBitmapFromUri(uri: Uri) {
        runCatching {
            // Two-pass decode: first read bounds, then decode with inSampleSize
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            options.inSampleSize = calculateInSampleSize(options, 1024, 1024)
            options.inJustDecodeBounds = false
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()?.let {
            AnalyticsManager.doubtImageCaptured(this, "gallery")
            showReviewMode(it)
        }
            ?: Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
    }

    // ─── State transitions ────────────────────────────────────────────────────

    private fun showReviewMode(bitmap: Bitmap) {
        reviewImageView.setImageBitmap(null)
        capturedBitmap?.recycle()
        capturedBitmap = bitmap
        reviewImageView.setImageBitmap(bitmap)
        cameraLayout.visibility = View.GONE
        reviewLayout.visibility = View.VISIBLE
        updateAnalyzeBtnText()
    }

    private fun showCameraMode() {
        reviewImageView.setImageBitmap(null)
        capturedBitmap?.recycle()
        capturedBitmap = null
        cameraLayout.visibility = View.VISIBLE
        reviewLayout.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        reviewImageView.setImageBitmap(null)
        capturedBitmap?.recycle()
        capturedBitmap = null
    }

    private fun updateScanCounter() {
        if (!::tvScanCounter.isInitialized) return
        if (PrefManager.isAllAccessUnlocked(this)) {
            tvScanCounter.text = "✨ Premium — Unlimited scans"
            tvScanCounter.setTextColor(goldPrimary)
        } else {
            val remaining = PrefManager.getRemainingScans(this)
            when {
                remaining == 0 -> {
                    tvScanCounter.text = "🔒 No scans left today"
                    tvScanCounter.setTextColor(Color.parseColor("#EF4444"))
                }
                remaining == 1 -> {
                    tvScanCounter.text = "⚠️ Last free scan today"
                    tvScanCounter.setTextColor(Color.parseColor("#F59E0B"))
                }
                else -> {
                    tvScanCounter.text = "$remaining scans left"
                    tvScanCounter.setTextColor(textTertiary)
                }
            }
        }
    }

    private fun updateAnalyzeBtnText() {
        if (!::analyzeBtn.isInitialized) return
        val canScan = PrefManager.canScanNow(this)
        analyzeBtn.text = if (canScan) "✨  Analyze with AI" else "🔒  Limit Reached"
    }

    // ─── Scan limit + paywall ─────────────────────────────────────────────────

    private fun onAnalyzeTapped() {
        if (isProcessing) return
        if (!com.jeeneet.mocktest.utils.NetworkUtils.isNetworkAvailable(this)) {
            showNoInternetToast()
            return
        }
        if (com.jeeneet.mocktest.BuildConfig.GEMINI_API_KEY.isBlank()) {
            Toast.makeText(this, "Set your Gemini API key in local.properties", Toast.LENGTH_LONG).show()
            return
        }
        if (!PrefManager.canScanNow(this)) {
            AnalyticsManager.scanLimitReached(this)
            showScanLimitDialog()
            return
        }
        analyzeImage()
    }

    private fun showScanLimitDialog() {
        val remaining = PrefManager.getRemainingScans(this)
        
        MaterialAlertDialogBuilder(this)
            .setTitle("🔒 Scans Finished")
            .setMessage(
                "You have used all your free scans.\n\n" +
                "⚡ Watch a short ad to unlock 5 more scans instantly!\n\n" +
                "👑 Go Premium for unlimited scans forever."
            )
            .setPositiveButton("👑 Go Premium") { _, _ ->
                AnalyticsManager.scanUpgradeClicked(this)
                ShopActivity.start(this)
            }
            .setNeutralButton("⚡ Watch Ad (+5 Scans)") { _, _ ->
                AdManager.showRewarded(
                    activity = this,
                    onRewarded = {
                        PrefManager.addScanRefill(this)
                        com.jeeneet.mocktest.data.repository.ScanRepository(this).recordAdRefill()
                        AnalyticsManager.scanAdWatched(this)
                        updateScanCounter()
                        updateAnalyzeBtnText()
                        Toast.makeText(this, "+5 scans unlocked! 🎉", Toast.LENGTH_SHORT).show()
                    },
                    onNotAvailable = {
                        Toast.makeText(this, "Ad not available. Try again later.", Toast.LENGTH_SHORT).show()
                    },
                    onLoading = {
                        Toast.makeText(this, "Loading ad…", Toast.LENGTH_SHORT).show()
                    },
                    placement = "scan_refill"
                )
            }
            .setNegativeButton("Maybe Later", null)
            .show()
    }

    // ─── Gemini API call ──────────────────────────────────────────────────────

    private fun analyzeImage() {
        val bitmap = capturedBitmap ?: return
        isProcessing = true
        loadingOverlay.visibility = View.VISIBLE
        analyzeBtn.isEnabled = false
        val apiStartTime = System.currentTimeMillis()
        AnalyticsManager.doubtCropConfirmed(this)
        AnalyticsManager.scanStarted(this)

        // Dynamic loading messages
        val messages = listOf(
            "Extracting text from image…",
            "Checking question memory…",
            "Applying local rules…",
            "Understanding problem…",
            "Generating solution…"
        )
        val msgJob = lifecycleScope.launch {
            var i = 0
            while (isProcessing) {
                runOnUiThread { tvLoadingDetail.text = messages[i % messages.size] }
                i++
                kotlinx.coroutines.delay(1500)
            }
        }

        val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)
        val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val extractedText = visionText.text.trim()
                
                lifecycleScope.launch {
                    val repository = GeminiRepository(this@ScanActivity)
                    
                    // 🔥 HYBRID LOGIC: 
                    // 1. If OCR is good, use Text API (which now includes Local Solver + Cache)
                    // 2. If OCR is weak, use Vision API (which includes Cache)
                    
                    val task = if (extractedText.length > 20) {
                        runOnUiThread { tvLoadingDetail.text = "OCR Success! Analyzing..." }
                        repository.solveTextQuestion(extractedText) { status ->
                            runOnUiThread { tvLoadingDetail.text = status }
                        }
                    } else {
                        runOnUiThread { tvLoadingDetail.text = "Weak text. Using Vision AI..." }
                        repository.solveQuestion(bitmap, extractedText) { status ->
                            runOnUiThread { tvLoadingDetail.text = status }
                        }
                    }

                    task.onSuccess { result ->
                        val duration = System.currentTimeMillis() - apiStartTime
                        AnalyticsManager.doubtApiLatency(this@ScanActivity, duration, isSuccess = true)
                        val fromCache = result.cacheStatus == CacheStatus.HIT
                        if (fromCache) AnalyticsManager.scanCacheHit(this@ScanActivity)
                        else {
                            PrefManager.incrementScanCount(this@ScanActivity)
                            com.jeeneet.mocktest.data.repository.ScanRepository(this@ScanActivity).recordScan()
                        }
                        
                        AnalyticsManager.scanCompleted(this@ScanActivity, fromCache)
                        isProcessing = false
                        msgJob.cancel()
                        loadingOverlay.visibility = View.GONE
                        updateScanCounter()
                        updateAnalyzeBtnText()
                        analyzeBtn.isEnabled = true
                        
                        AISolutionActivity.start(this@ScanActivity, result.markdown, result.modelUsed)
                    }
                    .onFailure { error ->
                        val duration = System.currentTimeMillis() - apiStartTime
                        AnalyticsManager.doubtApiLatency(this@ScanActivity, duration, isSuccess = false)
                        isProcessing = false
                        msgJob.cancel()
                        loadingOverlay.visibility = View.GONE
                        tvLoadingDetail.text = messages[0]
                        analyzeBtn.isEnabled = true
                        
                        val msg = error.message ?: "Something went wrong. Please try again."
                        val isRateLimit = msg.contains("rate", ignoreCase = true) ||
                                         msg.contains("wait", ignoreCase = true)
                                         
                        MaterialAlertDialogBuilder(this@ScanActivity)
                            .setTitle(if (isRateLimit) "⏳ AI Busy" else "Scan Failed")
                            .setMessage(if (isRateLimit)
                                "Too many requests right now.\n\nPlease wait 30–60 seconds and try again."
                                else msg)
                            .setPositiveButton("Try Again") { _, _ -> analyzeImage() }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
            .addOnFailureListener { e ->
                val duration = System.currentTimeMillis() - apiStartTime
                AnalyticsManager.doubtApiLatency(this@ScanActivity, duration, isSuccess = false)
                isProcessing = false
                msgJob.cancel()
                loadingOverlay.visibility = View.GONE
                analyzeBtn.isEnabled = true
                MaterialAlertDialogBuilder(this@ScanActivity)
                    .setTitle("OCR Failed")
                    .setMessage("Failed to extract text from the image. Error: ${e.message}")
                    .setPositiveButton("Try Again") { _, _ -> analyzeImage() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Decode a JPEG/PNG file safely for low-end devices.
     * Two-pass: read bounds first, then decode with inSampleSize so the
     * resulting bitmap never exceeds 1024×1024 px in memory.
     */
    private fun decodeSafeBitmap(path: String): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        options.inSampleSize = calculateInSampleSize(options, 1024, 1024)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(path, options)
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width  = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth  = width  / 2
            while (halfHeight / inSampleSize >= reqHeight &&
                   halfWidth  / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    // Int.dp / Int.dpF come from UiStyle.

    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, ScanActivity::class.java))
    }
}
