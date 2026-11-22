package com.timeless.triviaquiz.game

/**
 * The difficulties from first to last, with how many rounds of each a default game has. A theme
 * needs at least this many questions of each to load, however many rounds are played.
 */
val THEME_QUESTION_SPLIT: Map<String, Int> = linkedMapOf("easy" to 2, "medium" to 2, "hard" to 2)

val DEFAULT_ROUNDS: Int = THEME_QUESTION_SPLIT.values.sum()

/** What the host can choose from. */
val ROUND_OPTIONS: IntRange = 1..20

/**
 * The difficulty of each round: shared out as evenly as possible and never getting easier. When it
 * doesn't divide evenly, the earlier difficulties get the extra rounds.
 */
fun roundDifficulties(rounds: Int): List<String> {
    val difficulties = THEME_QUESTION_SPLIT.keys.toList()
    return List(rounds) { difficulties[it * difficulties.size / rounds] }
}

/** A question as written in a theme file. [used] is set once its answer has been revealed. */
data class ThemeQuestion(
    val difficulty: String,
    val question: String,
    val correctAnswer: String,
    val incorrectAnswers: List<String>,
    val explanation: String,
    val used: Boolean = false
)

/**
 * A question prepared for one turn, with its answers shuffled. Like [QuizQuestion] it must never be
 * serialised to a client: it carries both [correctAnswer] and [explanation].
 */
data class PartyQuestion(
    val difficulty: String,
    val question: String,
    val answers: List<String>,
    val correctAnswer: String,
    val explanation: String
)

/** A valid theme file. [id] is its file name, as two files may share a [name]. */
data class Theme(val id: String, val name: String, val questions: List<ThemeQuestion>) {

    val usedCount: Int
        get() = questions.count { it.used }

    /**
     * Picks one question of [difficulty], preferring unused ones, then ones used in earlier games.
     * Those in [askedThisGame] come last, as everyone has just seen their answers.
     */
    fun drawQuestion(difficulty: String, askedThisGame: Set<String>): PartyQuestion? =
        questions
            .filter { it.difficulty == difficulty }
            .shuffled()
            .minWithOrNull(compareBy({ it.question in askedThisGame }, { it.used })) // False sorts first.
            ?.let {
                PartyQuestion(
                    difficulty = it.difficulty,
                    question = it.question,
                    answers = (it.incorrectAnswers + it.correctAnswer).shuffled(),
                    correctAnswer = it.correctAnswer,
                    explanation = it.explanation
                )
            }
}
