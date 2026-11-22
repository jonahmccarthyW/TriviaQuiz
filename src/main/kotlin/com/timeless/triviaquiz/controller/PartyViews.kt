package com.timeless.triviaquiz.controller

import com.timeless.triviaquiz.game.GUESSER_POINTS
import com.timeless.triviaquiz.game.GameRoom
import com.timeless.triviaquiz.game.PartyStage
import com.timeless.triviaquiz.game.PartyTurn
import com.timeless.triviaquiz.game.Theme
import java.time.Duration
import java.time.Instant

/**
 * [id] lets the scoreboard follow a player as bars re-sort, and tells the host's Kick whom to
 * remove. It is no credential: who a device is comes only from its session.
 *
 * [correct] is null except during the reveal: any earlier and it would give the answer away to
 * whoever is still answering.
 */
data class PartyPlayerView(
    val id: String,
    val name: String,
    val score: Int,
    val picker: Boolean,
    val you: Boolean,
    val answered: Boolean,
    val correct: Boolean?
)

data class ThemeChoiceView(val id: String, val name: String)

/**
 * A Theme Party question while it is still being answered. As with [QuestionView], it exists to
 * omit `correctAnswer`, and here `explanation` too. [yourAnswer] is only ever the viewer's own.
 */
data class PartyQuestionView(
    val question: String,
    val answers: List<String>,
    val yourAnswer: String?
)

/**
 * A question at the reveal. This is the only view that carries the answer and explanation, and it
 * is only built in [PartyStage.REVEAL].
 */
data class PartyRevealView(
    val question: String,
    val correctAnswer: String,
    val explanation: String,
    val yourAnswer: String?,
    val pickerCorrect: Boolean,
    val othersCorrect: Int,
    val bonus: Int,
    val penalty: Int,
    val points: Int,
    val guesserPoints: Int
)

/** The Theme Party part of [RoomStateView], tailored to who is asking. */
data class PartyView(
    val stage: String,
    val removed: Boolean,
    val players: List<PartyPlayerView>,
    val round: Int,
    val rounds: Int,
    val difficulty: String?,
    val picker: String?,
    val nextPicker: String?,
    val yourPick: Boolean,
    val bonusNeedsSecond: Boolean?,
    val timeLimit: Boolean?,
    /** Time left rather than a deadline, as phones' clocks can't be trusted to match the laptop's. */
    val millisLeft: Long?,
    val millisAllowed: Long?,
    val theme: String?,
    val themes: List<ThemeChoiceView>?,
    val question: PartyQuestionView?,
    val reveal: PartyRevealView?,
    val winners: List<String>
)

/** Call with [GameRoom.mutex] held. The result shares no mutable state with the room. */
internal fun buildPartyView(room: GameRoom, viewerId: String?, themes: List<Theme>, now: Instant): PartyView {
    val removed = room.wasKicked(viewerId)
    val game = room.party
        ?: return PartyView(
            stage = "lobby",
            removed = removed,
            players = room.lobby.map {
                PartyPlayerView(
                    id = it.playerId,
                    name = it.name,
                    score = 0,
                    picker = false,
                    you = it.playerId == viewerId,
                    answered = false,
                    correct = null
                )
            },
            round = 0,
            rounds = 0, // Not final until the host presses start.
            difficulty = null,
            picker = null,
            nextPicker = null,
            yourPick = false,
            bonusNeedsSecond = null, // Neither setting is final until the host presses start.
            timeLimit = null,
            millisLeft = null,
            millisAllowed = null,
            theme = null,
            themes = null,
            question = null,
            reveal = null,
            winners = emptyList()
        )

    val picker = if (game.isOver) null else game.picker

    return PartyView(
        stage = game.stage.name.lowercase(),
        removed = removed,
        players = game.players.map {
            PartyPlayerView(
                id = it.playerId,
                name = it.name,
                score = game.scoreOf(it.playerId),
                picker = it.playerId == picker?.playerId,
                you = it.playerId == viewerId,
                answered = game.hasAnswered(it.playerId),
                correct = if (game.stage == PartyStage.REVEAL) game.turn?.isCorrect(it.playerId) else null
            )
        },
        round = game.roundIndex + 1,
        rounds = game.rounds,
        difficulty = game.difficulty,
        picker = picker?.name,
        nextPicker = game.nextPicker?.name,
        yourPick = viewerId != null && viewerId == picker?.playerId,
        bonusNeedsSecond = game.bonusNeedsSecond,
        timeLimit = game.timeLimit,
        millisLeft = game.turn?.deadline?.let { Duration.between(now, it).toMillis().coerceAtLeast(0) },
        millisAllowed = game.turn?.timeAllowed?.toMillis(),
        theme = game.turn?.themeName,
        themes = if (game.canPick(viewerId)) game.themeChoices(themes).map { ThemeChoiceView(it.id, it.name) } else null,
        question = game.turn?.takeIf { game.stage == PartyStage.ANSWERING }?.questionView(viewerId),
        reveal = game.turn?.takeIf { game.stage == PartyStage.REVEAL }?.revealView(viewerId),
        winners = game.winners().map { it.name }
    )
}

private fun PartyTurn.questionView(viewerId: String?) = PartyQuestionView(
    question = question.question,
    answers = question.answers,
    yourAnswer = answerBy(viewerId)
)

private fun PartyTurn.revealView(viewerId: String?): PartyRevealView? = score?.let {
    PartyRevealView(
        question = question.question,
        correctAnswer = question.correctAnswer,
        explanation = question.explanation,
        yourAnswer = answerBy(viewerId),
        pickerCorrect = it.pickerCorrect,
        othersCorrect = it.othersCorrect,
        bonus = it.bonus,
        penalty = it.penalty,
        points = it.total,
        guesserPoints = GUESSER_POINTS
    )
}
