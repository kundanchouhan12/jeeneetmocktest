package com.jeeneet.mocktest.ui.test

import android.app.Application
import android.os.CountDownTimer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jeeneet.mocktest.data.model.*
import com.jeeneet.mocktest.data.repository.AchievementManager
import com.jeeneet.mocktest.data.repository.MockTestRepository
import com.jeeneet.mocktest.utils.AnalyticsManager
import com.jeeneet.mocktest.utils.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TestViewModel(application: Application, private val repository: MockTestRepository) : AndroidViewModel(application) {

    // ─── State ───────────────────────────────────────────────────────────────

    private val _session = MutableLiveData<TestSession?>()
    val session: LiveData<TestSession?> = _session

    private val _currentIndex = MutableLiveData(0)
    val currentIndex: LiveData<Int> = _currentIndex

    private val _timeLeftSeconds = MutableLiveData(0L)
    val timeLeftSeconds: LiveData<Long> = _timeLeftSeconds

    private val _testFinished = MutableLiveData(false)
    val testFinished: LiveData<Boolean> = _testFinished

    private val _result = MutableLiveData<TestResult?>()
    val result: LiveData<TestResult?> = _result

    private var countDownTimer: CountDownTimer? = null
    private var questionViewStartMs = 0L
    private var isSimulationMode = false
    private var autoSaveTick = 0

    // ─── Start test ───────────────────────────────────────────────────────────

    fun startTest(config: ExamConfig, questions: List<Question>, isSimulation: Boolean = false) {
        this.isSimulationMode = isSimulation
        val sess = TestSession(config, questions)
        _session.value = sess
        _currentIndex.value = 0
        _testFinished.value = false
        questionViewStartMs = System.currentTimeMillis()
        startTimer(config.durationMinutes * 60L)
    }

    // ─── Navigation ───────────────────────────────────────────────────────────

    fun goToQuestion(index: Int) {
        recordCurrentQuestionTime()
        _currentIndex.value = index
    }
    fun nextQuestion() {
        val sess = _session.value ?: return
        val next = (_currentIndex.value ?: 0) + 1
        if (next < sess.questions.size) {
            recordCurrentQuestionTime()
            _currentIndex.value = next
        }
    }
    fun previousQuestion() {
        val prev = (_currentIndex.value ?: 1) - 1
        if (prev >= 0) {
            recordCurrentQuestionTime()
            _currentIndex.value = prev
        }
    }

    private fun recordCurrentQuestionTime() {
        val idx = _currentIndex.value ?: return
        val elapsed = System.currentTimeMillis() - questionViewStartMs
        _session.value?.questionTimeMs?.let { map ->
            map[idx] = (map[idx] ?: 0L) + elapsed
        }
        questionViewStartMs = System.currentTimeMillis()
    }

    // ─── Answer + review ──────────────────────────────────────────────────────

    fun selectAnswer(optionIndex: Int) {
        val idx = _currentIndex.value ?: return
        _session.value?.answerQuestion(idx, optionIndex)
        _session.notifyObservers()
    }

    fun toggleMarkForReview() {
        val idx = _currentIndex.value ?: return
        _session.value?.toggleReview(idx)
        _session.notifyObservers()
    }

    fun clearAnswer() {
        val idx = _currentIndex.value ?: return
        _session.value?.answers?.remove(idx)
        _session.notifyObservers()
    }

    // ─── Timer ────────────────────────────────────────────────────────────────

    private fun startTimer(totalSeconds: Long) {
        countDownTimer?.cancel()
        autoSaveTick = 0
        countDownTimer = object : CountDownTimer(totalSeconds * 1000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                _timeLeftSeconds.value = millisUntilFinished / 1000
                if (++autoSaveTick >= 60) {
                    autoSaveTick = 0
                    silentAutoSave()
                }
            }
            override fun onFinish() {
                _timeLeftSeconds.value = 0
                finishTest()
            }
        }.start()
    }

    private fun silentAutoSave() {
        val sess = _session.value ?: return
        recordCurrentQuestionTime()
        // Capture immutable snapshots on the main thread before going to background
        val config = sess.config
        val questions = sess.questions.toList()
        val answers = HashMap(sess.answers)
        val marked = HashSet(sess.markedForReview)
        val times = HashMap(sess.questionTimeMs)
        val currentIdx = _currentIndex.value ?: 0
        val timeLeft = _timeLeftSeconds.value ?: 0L
        // Gson serialization of the full question list is CPU-bound; run off the main thread
        viewModelScope.launch(Dispatchers.Default) {
            val gson = Gson()
            val saved = SavedTestSession(
                configJson          = gson.toJson(config),
                questionsJson       = gson.toJson(questions),
                answersJson         = gson.toJson(answers),
                markedForReviewJson = gson.toJson(marked),
                currentIndex        = currentIdx,
                timeLeftSeconds     = timeLeft,
                questionTimesJson   = gson.toJson(times)
            )
            PrefManager.saveTestSessionJson(getApplication(), gson.toJson(saved))
        }
        // does NOT cancel the timer — test continues uninterrupted
    }

    // ─── Finish + score ───────────────────────────────────────────────────────

    fun finishTest() {
        countDownTimer?.cancel()
        recordCurrentQuestionTime()
        val sess = _session.value ?: return
        val result = calculateResult(sess)
        val isDailyQuiz = sess.config.isDailyQuiz
        val isDailyVault = sess.config.isDailyVault
        viewModelScope.launch {
            PrefManager.clearSavedTestSession(getApplication())
            PrefManager.incrementTotalQuestionsSolved(getApplication(), result.attempted)
            
            if (isDailyQuiz) PrefManager.markDailyQuizDone(getApplication())
            
            if (isDailyVault) {
                AnalyticsManager.vaultCompleted(getApplication(), if(result.totalQuestions > 0) result.correct.toFloat() / result.totalQuestions else 0f)
                if (result.attempted == result.totalQuestions) {
                    PrefManager.addCoins(getApplication(), 50)
                    AchievementManager.checkVaultCompleted(getApplication(), result.attempted, result.totalQuestions)
                }
                PrefManager.markDailyVaultDone(getApplication(), result.examType)
                PrefManager.saveLastDailyVaultQuestionsJson(getApplication(), result.examType, result.questionsJson)
            }

            val savedId = repository.saveResult(getApplication(), result)
            _result.value = result.copy(id = savedId.toInt())
            _testFinished.value = true
            repository.updateStreak(getApplication())
        }
    }

    // ─── Session persistence (pause / resume) ────────────────────────────────

    /**
     * Serialises the current in-progress session to SharedPreferences so the user
     * can close the app and resume later from exactly where they left off.
     */
    fun saveSession() {
        val sess  = _session.value ?: return
        val gson  = Gson()
        recordCurrentQuestionTime()
        val saved = SavedTestSession(
            configJson           = gson.toJson(sess.config),
            questionsJson        = gson.toJson(sess.questions),
            answersJson          = gson.toJson(sess.answers),
            markedForReviewJson  = gson.toJson(sess.markedForReview),
            currentIndex         = _currentIndex.value ?: 0,
            timeLeftSeconds      = _timeLeftSeconds.value ?: 0L,
            questionTimesJson    = gson.toJson(sess.questionTimeMs)
        )
        PrefManager.saveTestSessionJson(getApplication(), gson.toJson(saved))
        countDownTimer?.cancel()
    }

    /**
     * Restores a previously saved session from SharedPreferences.
     * Returns true if a valid session was found and restored.
     */
    fun restoreSession(): Boolean {
        val json   = PrefManager.getSavedTestSessionJson(getApplication()) ?: return false
        val gson   = Gson()
        val saved  = gson.fromJson(json, SavedTestSession::class.java) ?: return false

        val config    = gson.fromJson(saved.configJson, ExamConfig::class.java)
        val questions = gson.fromJson<List<Question>>(
            saved.questionsJson, object : TypeToken<List<Question>>() {}.type
        )
        val answers   = gson.fromJson<MutableMap<Int, Int?>>(
            saved.answersJson, object : TypeToken<MutableMap<Int, Int?>>() {}.type
        ) ?: mutableMapOf()
        val marked    = gson.fromJson<MutableSet<Int>>(
            saved.markedForReviewJson, object : TypeToken<MutableSet<Int>>() {}.type
        ) ?: mutableSetOf()

        val times = if (saved.questionTimesJson.isNotEmpty()) {
            gson.fromJson<MutableMap<Int, Long>>(
                saved.questionTimesJson, object : TypeToken<MutableMap<Int, Long>>() {}.type
            ) ?: mutableMapOf()
        } else mutableMapOf()

        val sess = TestSession(config, questions)
        sess.answers.putAll(answers)
        sess.markedForReview.addAll(marked)
        sess.questionTimeMs.putAll(times)

        _session.value      = sess
        _currentIndex.value = saved.currentIndex
        _testFinished.value = false
        questionViewStartMs = System.currentTimeMillis()
        startTimer(saved.timeLeftSeconds)
        return true
    }

    private fun calculateResult(sess: TestSession): TestResult {
        val config = sess.config
        var correct = 0; var wrong = 0; var score = 0f

        sess.questions.forEachIndexed { idx, q ->
            val answer = sess.answers[idx]
            when {
                answer == null -> { /* unattempted */ }
                answer == q.correctOptionIndex -> {
                    correct++
                    score += if (config.difficultyScoring) {
                        when (q.difficulty) {
                            "Easy" -> maxOf(1f, config.correctMarks - 1f)
                            "Hard" -> config.correctMarks + 1f
                            else   -> config.correctMarks
                        }
                    } else config.correctMarks
                }
                else -> {
                    wrong++
                    score += config.negativeMarks
                }
            }
        }
        val attempted = correct + wrong
        // maxScore uses actual questions loaded, not config intent
        val maxScore = if (config.difficultyScoring) {
            sess.questions.sumOf { q ->
                when (q.difficulty) {
                    "Easy" -> maxOf(1f, config.correctMarks - 1f)
                    "Hard" -> config.correctMarks + 1f
                    else   -> config.correctMarks
                }.toDouble()
            }.toFloat()
        } else {
            sess.questions.size * config.correctMarks
        }
        val scorePercent = if (maxScore > 0) score / maxScore * 100 else 0f
        val percentile = when {
            scorePercent >= 90 -> (95 + (scorePercent - 90) * 0.4f).coerceAtMost(99.9f)
            scorePercent >= 75 -> (85 + (scorePercent - 75) * 0.67f).coerceAtMost(95.0f)
            scorePercent >= 50 -> (60 + (scorePercent - 50) * 1.0f).coerceAtMost(85.0f)
            scorePercent >= 25 -> (30 + (scorePercent - 25) * 1.2f).coerceAtMost(60.0f)
            else               -> (scorePercent * 1.2f).coerceIn(0f, 30f)
        }
        val timeTaken = sess.elapsedSeconds()

        val gson = com.google.gson.Gson()
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
        return TestResult(
            userId = uid,
            examType = config.examType,
            subject = config.subject ?: "Full Paper",
            totalQuestions = sess.questions.size,
            attempted = attempted,
            correct = correct,
            wrong = wrong,
            score = score,
            maxScore = maxScore,
            percentile = percentile,
            timeTakenSeconds = timeTaken,
            questionsJson = gson.toJson(sess.questions),
            answersJson = gson.toJson(sess.answers),
            questionTimesJson = gson.toJson(sess.questionTimeMs),
            isSimulation = isSimulationMode
        )
    }

    // ─── Derived helpers ─────────────────────────────────────────────────────

    fun currentQuestion(): Question? {
        val sess = _session.value ?: return null
        val idx = _currentIndex.value ?: 0
        return if (idx < sess.questions.size) sess.questions[idx] else null
    }

    fun questionStatus(index: Int): QuestionStatus {
        val sess = _session.value ?: return QuestionStatus.NOT_VISITED
        return when {
            sess.isMarkedForReview(index) && sess.isAnswered(index) -> QuestionStatus.ANSWERED_MARKED
            sess.isMarkedForReview(index)                           -> QuestionStatus.MARKED_FOR_REVIEW
            sess.isAnswered(index)                                  -> QuestionStatus.ANSWERED
            else                                                    -> QuestionStatus.NOT_ANSWERED
        }
    }

    enum class QuestionStatus {
        NOT_VISITED, NOT_ANSWERED, ANSWERED, MARKED_FOR_REVIEW, ANSWERED_MARKED
    }

    override fun onCleared() {
        super.onCleared()
        countDownTimer?.cancel()
    }

    // Force LiveData observers to be notified on mutable state changes
    private fun <T> MutableLiveData<T>.notifyObservers() { value = value }
}

// ─── Factory ─────────────────────────────────────────────────────────────────

class TestViewModelFactory(private val application: Application, private val repo: MockTestRepository) :
    androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return TestViewModel(application, repo) as T
    }
}
