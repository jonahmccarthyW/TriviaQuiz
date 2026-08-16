package com.timeless.triviaquiz.controller

import com.timeless.triviaquiz.game.DIFFICULTIES
import com.timeless.triviaquiz.game.GameRoom
import com.timeless.triviaquiz.game.MultiplayerGame
import com.timeless.triviaquiz.game.RoomFeedback
import com.timeless.triviaquiz.game.RoomPhase
import com.timeless.triviaquiz.game.TurnOutcome
import com.timeless.triviaquiz.service.GameRoomService
import com.timeless.triviaquiz.service.QuestionDealer
import jakarta.servlet.http.HttpSession
import kotlinx.coroutines.sync.withLock
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * A question as the browser is allowed to see it.
 *
 * Deliberately *not* [com.timeless.triviaquiz.game.QuizQuestion]: that carries `correctAnswer`,
 * which was safe while Mustache rendered server-side and never emitted it, but would ship the
 * answer to every phone the moment it were serialised to JSON.
 */
data class QuestionView(
    val category: String,
    val difficulty: String,
    val question: String,
    val answers: List<String>,
    val step: Int,
    val steps: Int
)

data class PlayerView(
    val name: String,
    val cleared: Int,
    val total: Int,
    val current: Boolean,
    val you: Boolean
)

data class FeedbackView(
    val answeredBy: String,
    val question: String,
    val givenAnswer: String?,
    val correctAnswer: String,
    val correct: Boolean
)

/** Everything one device needs to draw its screen, tailored to who is asking. */
data class RoomStateView(
    val version: Long,
    val phase: String,
    val you: String?,
    val yourTurn: Boolean,
    val canStart: Boolean,
    val players: List<PlayerView>,
    val question: QuestionView?,
    val feedback: FeedbackView?,
    val winner: String?,
    val notice: String?
)

data class AnswerRequest(val version: Long, val answer: String?)

/**
 * The polling API every device in a room talks to. One endpoint to read the room and one to answer;
 * both return the same tailored view so a submitted answer updates the screen without waiting for
 * the next poll.
 *
 * Reading the room is not purely a read: whichever device polls first once the previous answer's
 * feedback has expired is the one that goes and fetches the next question. That keeps the game
 * moving with no scheduler and no "next question" button for someone to forget to press.
 */
@RestController
class RoomApiController(
    private val gameRoomService: GameRoomService,
    private val questionDealer: QuestionDealer
) {

    @GetMapping("/api/room/{code}/state")
    suspend fun state(@PathVariable code: String, session: HttpSession): ResponseEntity<RoomStateView> {
        val room = gameRoomService.find(code) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(advanceAndBuild(room, viewerId(session, room)))
    }

    @PostMapping("/api/room/{code}/answer")
    suspend fun answer(
        @PathVariable code: String,
        @RequestBody body: AnswerRequest,
        session: HttpSession
    ): ResponseEntity<RoomStateView> {
        val room = gameRoomService.find(code) ?: return ResponseEntity.notFound().build()
        val viewerId = viewerId(session, room)

        room.mutex.withLock {
            val game = room.game
            val question = game?.currentQuestion

            // Not your turn, nothing to answer, or a stale submission (double tap, or a screen the
            // player had open from before the turn moved on). Fall through to returning the current
            // state so the device re-syncs instead of showing an error.
            if (game == null || question == null ||
                viewerId == null || viewerId != room.currentPlayerId() ||
                body.version != room.version
            ) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(buildView(room, viewerId))
            }

            val correct = body.answer != null && body.answer == question.correctAnswer
            val answeredBy = game.currentPlayer.name
            val outcome = game.applyAnswer(correct)
            if (outcome == TurnOutcome.TURN_OVER) {
                game.endTurn()
            }

            room.feedback = RoomFeedback(
                answeredBy = answeredBy,
                question = question.question,
                givenAnswer = body.answer,
                correctAnswer = question.correctAnswer,
                correct = correct,
                until = Instant.now().plus(GameRoomService.FEEDBACK_DURATION)
            )
            room.touch()
        }

        return ResponseEntity.ok(advanceAndBuild(room, viewerId))
    }

    /**
     * Expires stale feedback, deals the next question if one is needed, then builds the view.
     *
     * Exactly one caller claims the fetch ([GameRoom.dealing]) and does it *outside* the mutex,
     * because it is a network call: everyone else polls straight through and sees a question-less
     * state for a moment rather than piling up behind it. Failures back off via
     * [GameRoom.nextDealAttempt] so a struggling opentdb isn't retried once per second per device.
     */
    private suspend fun advanceAndBuild(room: GameRoom, viewerId: String?): RoomStateView {
        val game: MultiplayerGame

        room.mutex.withLock {
            room.keepAlive()

            if (room.feedback?.until?.isBefore(Instant.now()) == true) {
                room.feedback = null
                room.touch()
            }

            val current = room.game
            val needsQuestion = current != null && !current.isOver && room.feedback == null &&
                current.currentQuestion == null && !room.dealing &&
                Instant.now().isAfter(room.nextDealAttempt)

            if (!needsQuestion || current == null) return buildView(room, viewerId)

            room.dealing = true
            game = current
        }

        // runCatching so an unexpected failure can never leave `dealing` stuck true, which would
        // wedge the room: no other poll would ever claim the fetch again.
        val dealt = runCatching { questionDealer.dealNextQuestion(game) }.getOrDefault(false)

        return room.mutex.withLock {
            room.dealing = false
            if (dealt) {
                room.notice = null
                questionDealer.prefetchAhead(game)
            } else {
                room.notice = "Trouble reaching the question service — retrying."
                room.nextDealAttempt = Instant.now().plus(GameRoomService.DEAL_RETRY_DELAY)
            }
            room.touch()
            buildView(room, viewerId)
        }
    }

    private fun buildView(room: GameRoom, viewerId: String?): RoomStateView {
        val game = room.game
        val currentId = room.currentPlayerId()
        val question = game?.currentQuestion?.takeIf { room.feedback == null }

        val players = if (game == null) {
            room.lobby.map {
                PlayerView(
                    name = it.name,
                    cleared = 0,
                    total = it.categories.size,
                    current = false,
                    you = it.playerId == viewerId
                )
            }
        } else {
            // playerIds is built from the lobby at start, so positions line up with game.players.
            room.playerIds.zip(game.players) { id, player ->
                PlayerView(
                    name = player.name,
                    cleared = player.clearedCount,
                    total = player.categories.size,
                    current = id == currentId,
                    you = id == viewerId
                )
            }
        }

        return RoomStateView(
            version = room.version,
            phase = room.phase.name.lowercase(),
            you = room.lobby.firstOrNull { it.playerId == viewerId }?.name,
            yourTurn = viewerId != null && viewerId == currentId && question != null,
            canStart = room.phase == RoomPhase.LOBBY && room.lobby.size >= GameRoomService.MIN_PLAYERS,
            players = players,
            question = question?.let {
                QuestionView(
                    category = it.category,
                    difficulty = it.difficulty,
                    question = it.question,
                    answers = it.answers,
                    step = game.currentStep + 1,
                    steps = DIFFICULTIES.size
                )
            },
            feedback = room.feedback?.let {
                FeedbackView(
                    answeredBy = it.answeredBy,
                    question = it.question,
                    givenAnswer = it.givenAnswer,
                    correctAnswer = it.correctAnswer,
                    correct = it.correct
                )
            },
            winner = game?.winnerName,
            notice = room.notice
        )
    }

    /** The player this browser is, or null when the host (or a bystander) is watching. */
    private fun viewerId(session: HttpSession, room: GameRoom): String? {
        if (session.getAttribute(RoomSession.ROOM_CODE) != room.code) return null
        return session.getAttribute(RoomSession.PLAYER_ID) as? String
    }
}
