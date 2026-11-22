package com.timeless.triviaquiz.game

/** What the picker earns for getting their question right. */
const val PICKER_BONUS = 5

/** What each other player's correct answer costs the picker. */
const val PENALTY_PER_OTHER_CORRECT = 1

/** The picker's result on one question. Nobody else's score moves. */
data class QuestionScore(
    val pickerCorrect: Boolean,
    val othersCorrect: Int,
    val bonus: Int,
    val penalty: Int
) {
    val total: Int
        get() = bonus - penalty
}

/**
 * Scores one Theme Party question for the picker. The bonus and the penalty are independent: the
 * penalty applies whether or not the picker was right, and can take their score below zero.
 *
 * @param othersCorrect how many players other than the picker answered correctly.
 * @param bonusNeedsSecond when set, the bonus only counts if at least one other player was right.
 */
fun scoreQuestion(pickerCorrect: Boolean, othersCorrect: Int, bonusNeedsSecond: Boolean): QuestionScore {
    val bonusAllowed = !bonusNeedsSecond || othersCorrect > 0
    return QuestionScore(
        pickerCorrect = pickerCorrect,
        othersCorrect = othersCorrect,
        bonus = if (pickerCorrect && bonusAllowed) PICKER_BONUS else 0,
        penalty = othersCorrect * PENALTY_PER_OTHER_CORRECT
    )
}
