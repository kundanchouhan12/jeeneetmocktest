package com.jeeneet.mocktest

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jeeneet.mocktest.data.repository.CurriculumRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CurriculumRepositoryTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `curriculum json loads and parses all official units`() {
        val jeeMaths = CurriculumRepository.getUnits(ctx, "JEE", "Maths")
        val jeePhysics = CurriculumRepository.getUnits(ctx, "JEE", "Physics")
        val jeeChemistry = CurriculumRepository.getUnits(ctx, "JEE", "Chemistry")
        val neetBio = CurriculumRepository.getUnits(ctx, "NEET", "Biology")

        assertEquals(14, jeeMaths.size)
        assertEquals(20, jeePhysics.size)
        assertEquals(20, jeeChemistry.size)
        assertEquals(10, neetBio.size)
    }

    @Test
    fun `findUnit resolves exact unit name and ncert chapter aliases`() {
        // Direct unit name match
        val unitDirect = CurriculumRepository.findUnit(ctx, "JEE", "Physics", "Kinematics")
        assertNotNull(unitDirect)
        assertEquals("Kinematics", unitDirect?.unitName)

        // NCERT chapter alias match
        val unitByAlias = CurriculumRepository.findUnit(ctx, "JEE", "Physics", "Motion in a Straight Line")
        assertNotNull(unitByAlias)
        assertEquals("Kinematics", unitByAlias?.unitName)

        // Chemistry alias match
        val chemUnit = CurriculumRepository.findUnit(ctx, "JEE", "Chemistry", "General Organic Chemistry")
        assertNotNull(chemUnit)
        assertEquals("Basic Principles of Organic Chemistry", chemUnit?.unitName)
    }

    @Test
    fun `getTopics returns granular NCERT topics`() {
        val topics = CurriculumRepository.getTopics(ctx, "JEE", "Physics", "Kinematics")
        assertTrue("Expected multiple topics for Kinematics, got ${topics.size}", topics.isNotEmpty())
        
        val topicNames = topics.map { it.topic }
        assertTrue(topicNames.any { it.contains("Motion in a Straight Line", ignoreCase = true) || it.contains("Uniformly Accelerated", ignoreCase = true) || it.contains("Projectile", ignoreCase = true) })
    }

    @Test
    fun `getAllowedModes returns valid STEM modes`() {
        val modes = CurriculumRepository.getAllowedModes(ctx, "JEE", "Physics", "Kinematics")
        assertTrue("Modes should contain TEXT", modes.contains("TEXT"))
        assertTrue("Modes should contain NUMERICAL", modes.contains("NUMERICAL"))
    }

    @Test
    fun `isTopicAllowed validates approved topics and rejects invalid ones`() {
        val valid = CurriculumRepository.isTopicAllowed(ctx, "NEET", "Biology", "Genetics & Evolution", "Mendel's Laws")
        assertTrue("Expected Mendel's Laws to be allowed", valid)

        val invalid = CurriculumRepository.isTopicAllowed(ctx, "NEET", "Biology", "Genetics & Evolution", "Completely Fake Topic That Does Not Exist 12345")
        assertFalse("Expected fake topic to be rejected", invalid)
    }
}
