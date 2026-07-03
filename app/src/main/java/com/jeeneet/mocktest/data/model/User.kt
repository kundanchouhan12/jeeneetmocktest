package com.jeeneet.mocktest.data.model

import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName

@IgnoreExtraProperties
data class User(
    @get:PropertyName("uid")
    val uid: String = "",
    
    @get:PropertyName("username")
    val username: String = "",
    
    @get:PropertyName("email")
    val email: String = "",
    
    @get:PropertyName("age")
    val age: Int = 0,
    
    @get:PropertyName("termsAccepted")
    val termsAccepted: Boolean = false,
    
    @get:PropertyName("createdAt")
    val createdAt: Long = System.currentTimeMillis()
)
