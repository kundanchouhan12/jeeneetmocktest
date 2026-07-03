package com.jeeneet.mocktest

import android.content.Context
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import com.jeeneet.mocktest.admob.IAPManager
import com.android.billingclient.api.BillingClient
import io.mockk.*
import io.mockk.impl.annotations.MockK
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BackgroundSyncTest {

    @MockK
    private lateinit var mockContext: Context

    @MockK
    private lateinit var mockAuth: FirebaseAuth

    @MockK
    private lateinit var mockUser: FirebaseUser

    @MockK
    private lateinit var mockFirestore: FirebaseFirestore

    @MockK
    private lateinit var mockUsersCollection: CollectionReference

    @MockK
    private lateinit var mockUserDoc: DocumentReference

    @MockK
    private lateinit var mockPurchasesCollection: CollectionReference

    @MockK
    private lateinit var mockTask: Task<QuerySnapshot>

    @MockK
    private lateinit var mockQuerySnapshot: QuerySnapshot

    @MockK
    private lateinit var mockDocSnapshot: DocumentSnapshot

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        
        // Mock BillingClient to prevent initialization crash
        mockkStatic(BillingClient::class)
        val mockBuilder = mockk<BillingClient.Builder>(relaxed = true)
        val mockBillingClient = mockk<BillingClient>(relaxed = true)
        every { BillingClient.newBuilder(any()) } returns mockBuilder
        every { mockBuilder.setListener(any()) } returns mockBuilder
        // enablePendingPurchases() changed in billing-ktx 7.x
        every { mockBuilder.enablePendingPurchases(any()) } returns mockBuilder
        every { mockBuilder.build() } returns mockBillingClient

        // Fix for BillingClient initialization needing applicationContext
        every { mockContext.applicationContext } returns mockContext
        
        // Mock Android Log
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        
        // Mock FirebaseAuth
        mockkStatic(FirebaseAuth::class)
        every { FirebaseAuth.getInstance() } returns mockAuth
        every { mockAuth.currentUser } returns mockUser
        every { mockUser.uid } returns "test_user_id"

        // Mock FirebaseFirestore
        mockkStatic(FirebaseFirestore::class)
        every { FirebaseFirestore.getInstance() } returns mockFirestore
        
        // Mock Firestore hierarchy
        every { mockFirestore.collection("users") } returns mockUsersCollection
        every { mockUsersCollection.document("test_user_id") } returns mockUserDoc
        every { mockUserDoc.collection("purchases") } returns mockPurchasesCollection
        every { mockPurchasesCollection.get() } returns mockTask

        // Set up the Task mock to immediately succeed with our mockQuerySnapshot
        val successSlot = slot<OnSuccessListener<QuerySnapshot>>()
        every { mockTask.addOnSuccessListener(capture(successSlot)) } answers {
            successSlot.captured.onSuccess(mockQuerySnapshot)
            mockTask
        }
        val failureSlot = slot<OnFailureListener>()
        every { mockTask.addOnFailureListener(capture(failureSlot)) } answers {
            mockTask
        }

        // Mock the query snapshot to return empty list, avoiding MockK final method issues on DocumentSnapshot
        every { mockQuerySnapshot.documents } returns emptyList()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun testSyncPurchasesFromFirestoreExecutesCallback() {
        val iapManager = IAPManager(mockContext)
        val latch = CountDownLatch(1)
        var callbackExecuted = false

        // In a real app, this network call is asynchronous on a background thread.
        // We verify that the onComplete callback is fired successfully.
        iapManager.syncPurchasesFromFirestore {
            callbackExecuted = true
            latch.countDown()
        }

        // Wait up to 2 seconds for the callback
        latch.await(2, TimeUnit.SECONDS)

        assertTrue("Background sync callback should be executed upon completion", callbackExecuted)
        
        // Verify that Firestore was queried
        verify(exactly = 1) { mockPurchasesCollection.get() }
    }
}
