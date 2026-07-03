package com.jeeneet.mocktest.data.model

data class QuestionExposure(
    val questionId: Int = 0,
    val seenCount: Int = 0,
    val correctCount: Int = 0,
    val lastSeenAt: Long = 0,
    val avgTimeTakenMs: Long = 0
)
