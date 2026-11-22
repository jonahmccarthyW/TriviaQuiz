package com.timeless.triviaquiz.game

import kotlin.test.Test
import kotlin.test.assertEquals

/** The Theme Party scoring function (no Spring, no network). */
class ThemePartyScoringTest {

    @Test
    fun `bonus on with no other correct answer gives the picker nothing`() {
        val score = scoreQuestion(pickerCorrect = true, othersCorrect = 0, bonusNeedsSecond = true)

        assertEquals(0, score.bonus)
        assertEquals(0, score.total)
    }

    @Test
    fun `bonus on with one other correct answer gives the picker five, less that player's one`() {
        val score = scoreQuestion(pickerCorrect = true, othersCorrect = 1, bonusNeedsSecond = true)

        assertEquals(5, score.bonus)
        assertEquals(1, score.penalty)
        assertEquals(4, score.total)
    }

    @Test
    fun `bonus off gives the picker five however many others were right`() {
        for (others in 0..7) {
            val score = scoreQuestion(pickerCorrect = true, othersCorrect = others, bonusNeedsSecond = false)

            assertEquals(5, score.bonus, "$others others correct")
        }
    }

    @Test
    fun `each other correct answer costs one, whatever the rule and whether or not the picker was right`() {
        for (bonusNeedsSecond in listOf(true, false)) {
            for (pickerCorrect in listOf(true, false)) {
                for (others in 0..7) {
                    val score = scoreQuestion(pickerCorrect, others, bonusNeedsSecond)

                    assertEquals(others, score.penalty)
                    assertEquals(score.bonus - others, score.total)
                }
            }
        }
    }

    @Test
    fun `a wrong picker never gets the bonus`() {
        for (bonusNeedsSecond in listOf(true, false)) {
            for (others in 0..7) {
                assertEquals(0, scoreQuestion(false, others, bonusNeedsSecond).bonus)
            }
        }
    }
}
