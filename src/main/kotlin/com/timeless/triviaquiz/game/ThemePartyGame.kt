package com.timeless.triviaquiz.game

import java.time.Duration
import java.time.Instant

/** With the time limit on, a question starts with this long for each player. */
val TIME_PER_PLAYER: Duration = Duration.ofSeconds(4)

/** How much each answer takes off the clock for those still to answer. */
val TIME_CUT_PER_ANSWER: Duration = Duration.ofSeconds(2)

/** Answers can't push the clock below this. From here it only counts down. */
val TIME_CUT_FLOOR: Duration = Duration.ofSeconds(3)

/** Where a Theme Party game is within the current turn. */
enum class PartyStage { PICKING, ANSWERING, REVEAL, FINISHED }

data class PartyPlayer(val playerId: String, val name: String)

/** One picker's turn: the question everyone is answering and, from the reveal on, its score. */
class PartyTurn(
    val themeId: String,
    val themeName: String,
    val question: PartyQuestion,
    /** What the clock started at, or null without a time limit. */
    val timeAllowed: Duration?,
    openedAt: Instant
) {

    /** When answering ends. Null without a time limit, and once revealed. */
    var deadline: Instant? = timeAllowed?.let { openedAt.plus(it) }
        internal set

    private val answers = mutableMapOf<String, String>()

    var score: QuestionScore? = null
        internal set

    fun answerBy(playerId: String?): String? = playerId?.let { answers[it] }

    fun isCorrect(playerId: String): Boolean = answerBy(playerId) == question.correctAnswer

    /** Records an answer. The first one stands; returns false for a repeat or one not on offer. */
    internal fun record(playerId: String, answer: String): Boolean {
        if (answer !in question.answers) return false
        return answers.putIfAbsent(playerId, answer) == null
    }
}

/**
 * The Theme Party rules: a round is one turn per player, in join order, and a turn is one question
 * that everyone answers. A turn is revealed when all current players have answered or, with
 * [timeLimit], when the clock runs out. Only a player's [next] moves it on from there. Like
 * [MultiplayerGame] it has no Spring or network dependencies: time is passed in, and the caller
 * provides the locking ([GameRoom.mutex]).
 *
 * [bonusNeedsSecond] and [timeLimit] are fixed at construction, which is what locks the host's
 * settings for the game.
 */
class ThemePartyGame(
    players: List<PartyPlayer>,
    val bonusNeedsSecond: Boolean,
    val timeLimit: Boolean = false
) {

    private val seats = players.toMutableList()

    /** Everyone still in the game, in turn order. */
    val players: List<PartyPlayer>
        get() = seats

    var stage: PartyStage = PartyStage.PICKING
        private set

    var pickerIndex: Int = 0
        private set

    /** Zero-based. */
    var roundIndex: Int = 0
        private set

    /** Null while the picker is choosing and once the game has finished. */
    var turn: PartyTurn? = null
        private set

    private val scores = players.associate { it.playerId to 0 }.toMutableMap()

    // Kept as well as the files' used flags: once a theme is all used, or if saving a flag failed,
    // this is what stops a question coming back minutes after its answer was shown.
    private val askedQuestions = mutableSetOf<String>()

    val picker: PartyPlayer
        get() = seats[pickerIndex]

    val difficulty: String
        get() = ROUND_DIFFICULTIES[roundIndex]

    val isOver: Boolean
        get() = stage == PartyStage.FINISHED

    /** Who picks after this turn, or null if it is the last one. */
    val nextPicker: PartyPlayer?
        get() {
            val lastTurn = roundIndex == ROUND_DIFFICULTIES.lastIndex && pickerIndex == seats.lastIndex
            return if (isOver || lastTurn) null else seats[(pickerIndex + 1) % seats.size]
        }

    fun scoreOf(playerId: String): Int = scores.getValue(playerId)

    fun canPick(playerId: String?): Boolean = stage == PartyStage.PICKING && playerId == picker.playerId

    fun hasAnswered(playerId: String): Boolean = turn?.answerBy(playerId) != null

    /** Everyone sharing the highest score, or empty until the game is over. */
    fun winners(): List<PartyPlayer> {
        if (!isOver) return emptyList()
        val best = seats.maxOfOrNull { scoreOf(it.playerId) } ?: return emptyList()
        return seats.filter { scoreOf(it.playerId) == best }
    }

    /** Deals one question of this round's difficulty from the picker's theme. */
    fun pickTheme(playerId: String, theme: Theme, now: Instant = Instant.now()): Boolean {
        if (!canPick(playerId)) return false
        val question = theme.drawQuestion(difficulty, askedQuestions) ?: return false

        val timeAllowed = if (timeLimit) TIME_PER_PLAYER.multipliedBy(seats.size.toLong()) else null
        turn = PartyTurn(theme.id, theme.name, question, timeAllowed, openedAt = now)
        stage = PartyStage.ANSWERING
        return true
    }

    /** Records one answer, then either reveals, if it was the last, or cuts the clock. */
    fun answer(playerId: String, answer: String, now: Instant = Instant.now()): Boolean {
        val current = turn ?: return false
        if (stage != PartyStage.ANSWERING || !isPlaying(playerId)) return false
        if (timeIsUp(current, now) || !current.record(playerId, answer)) return false

        revealIfAllAnswered(current)
        if (stage == PartyStage.ANSWERING) cutTime(current, now)
        return true
    }

    /** Reveals if the clock has run out. Returns whether it did. */
    fun expire(now: Instant): Boolean {
        val current = turn ?: return false
        if (stage != PartyStage.ANSWERING || !timeIsUp(current, now)) return false

        reveal(current)
        return true
    }

    /** Ends a revealed turn. Any player may do it. */
    fun next(playerId: String): Boolean {
        if (stage != PartyStage.REVEAL || !isPlaying(playerId)) return false

        turn = null
        pickerIndex++
        startTurn()
        return true
    }

    /**
     * Takes a player out of the game. If it was their turn, the turn is dropped and passes on;
     * otherwise the turn carries on without waiting for them.
     */
    fun remove(playerId: String): Boolean {
        val index = seats.indexOfFirst { it.playerId == playerId }
        if (isOver || index < 0) return false

        val wasPicker = index == pickerIndex
        seats.removeAt(index)
        if (index < pickerIndex) pickerIndex--

        when {
            seats.size < 2 -> finish()
            wasPicker -> {
                turn = null
                startTurn() // pickerIndex already points at whoever was next.
            }
            stage == PartyStage.ANSWERING -> turn?.let { revealIfAllAnswered(it) }
        }
        return true
    }

    private fun isPlaying(playerId: String): Boolean = seats.any { it.playerId == playerId }

    private fun revealIfAllAnswered(current: PartyTurn) {
        if (seats.all { current.answerBy(it.playerId) != null }) reveal(current)
    }

    private fun timeIsUp(current: PartyTurn, now: Instant): Boolean =
        current.deadline?.let { !now.isBefore(it) } == true

    private fun cutTime(current: PartyTurn, now: Instant) {
        val deadline = current.deadline ?: return
        val floor = now.plus(TIME_CUT_FLOOR)
        if (deadline > floor) current.deadline = maxOf(floor, deadline.minus(TIME_CUT_PER_ANSWER))
    }

    /** Anyone who hasn't answered by now counts as wrong. */
    private fun reveal(current: PartyTurn) {
        val pickerId = picker.playerId
        val correctIds = seats
            .map { it.playerId }
            .filter { current.isCorrect(it) }
        val score = scoreQuestion(
            pickerCorrect = pickerId in correctIds,
            othersCorrect = correctIds.count { it != pickerId },
            bonusNeedsSecond = bonusNeedsSecond
        )
        current.score = score
        current.deadline = null
        scores[pickerId] = scoreOf(pickerId) + score.total
        askedQuestions.add(current.question.question)
        stage = PartyStage.REVEAL
    }

    /** Starts the turn at [pickerIndex], which is one past the last seat when a round has ended. */
    private fun startTurn() {
        if (pickerIndex >= seats.size) {
            if (roundIndex == ROUND_DIFFICULTIES.lastIndex) return finish()
            pickerIndex = 0
            roundIndex++
        }
        stage = PartyStage.PICKING
    }

    private fun finish() {
        turn = null
        pickerIndex = 0
        stage = PartyStage.FINISHED
    }
}
