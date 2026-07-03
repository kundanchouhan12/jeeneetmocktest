package com.jeeneet.mocktest.data.repository

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.jeeneet.mocktest.utils.PrefManager

class ScanRepository(private val context: Context) {
    private val TAG = "ScanRepository"
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    data class ScanData(
        val day: Long = 0,
        val usedToday: Int = 0,
        val refillsToday: Int = 0
    )

    fun syncWithCloud(onComplete: (ScanData?) -> Unit = {}) {
        val uid = auth.currentUser?.uid ?: return
        val today = System.currentTimeMillis() / 86_400_000

        db.collection("users").document(uid)
            .get()
            .addOnSuccessListener { doc ->
                val cloudDay = doc.getLong("scan_day") ?: -1L
                var used = doc.getLong("scans_used_today")?.toInt() ?: 0
                var refills = doc.getLong("ad_refills_today")?.toInt() ?: 0

                // Reset if cloud data is from a previous day
                if (cloudDay < today) {
                    used = 0
                    refills = 0
                    updateCloud(today, used, refills)
                }

                PrefManager.updateScanData(context, used, refills, today)
                Log.d(TAG, "Synced from Cloud: used=$used, refills=$refills, day=$today")
                onComplete(ScanData(today, used, refills))
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to sync scan data: ${e.message}")
                onComplete(null)
            }
    }

    fun recordScan() {
        val uid = auth.currentUser?.uid ?: return
        val today = System.currentTimeMillis() / 86_400_000
        val used = PrefManager.getScanCountToday(context)
        val refills = PrefManager.getScanAdBonusToday(context) // This is now 'refills'

        updateCloud(today, used, refills)
    }

    fun recordAdRefill() {
        val uid = auth.currentUser?.uid ?: return
        val today = System.currentTimeMillis() / 86_400_000
        val used = PrefManager.getScanCountToday(context)
        val refills = PrefManager.getScanAdBonusToday(context)

        updateCloud(today, used, refills)
    }

    private fun updateCloud(day: Long, used: Int, refills: Int) {
        val uid = auth.currentUser?.uid ?: return
        val data = mapOf(
            "scan_day" to day,
            "scans_used_today" to used,
            "ad_refills_today" to refills
        )
        db.collection("users").document(uid)
            .update(data)
            .addOnFailureListener {
                // If document doesn't exist or field update fails, use set with merge
                db.collection("users").document(uid)
                    .set(data, com.google.firebase.firestore.SetOptions.merge())
            }
    }
}
