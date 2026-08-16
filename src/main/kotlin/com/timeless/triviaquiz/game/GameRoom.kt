package com.timeless.triviaquiz.game

import kotlinx.coroutines.sync.Mutex
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Where a room is in its lifecycle. */
enum class RoomPhase { LOBBY, PLAYING, FINISHED }

/**
 * A player who joined a room from their own device. [playerId] is minted when they join and kept
 * in that device's HttpSession; it is how the server knows which browser is allowed to answer the
 * current question.
 */
data class LobbyPlayer(
    val playerId: String,
    val name: String,
    val categories: List<MpCategory>
)

/**
 * The most recently graded answer. Held on the room (not per device) so every player sees the same
 * feedback at the same time, and cleared once [until] passes so play moves on without anyone tapping.
 */
data class RoomFeedback(
    val answeredBy: String,
    val question: String,
    val givenAnswer: String?,
    val correctAnswer: String,
    val correct: Boolean,
    val until: Instant
)

/**
 * One networked game: the lobby of devices that have joined, and — once the host starts — the
 * [MultiplayerGame] state machine that drives play. The game rules themselves are untouched; this
 * type only adds what a room shared by several devices needs on top of them:
 *
 *  - a [mutex], because several devices poll and answer concurrently (unlike the hotseat game,
 *    where one browser's HttpSession made every request naturally serial);
 *  - a [version] that increments on every state change, so clients can skip re-rendering and so a
 *    double-tapped answer can be rejected as stale;
 *  - [playerIds], which maps positions in [MultiplayerGame.players] back to the device that owns
 *    them — [MpPlayer] has no identity of its own.
 */
class GameRoom(val code: String) {

    val mutex = Mutex()

    /**
     * Devices that have joined. Ordered; the game's player order follows it. Copy-on-write because
     * the "have you joined already?" check reads it outside [mutex] on every page load.
     */
    val lobby: MutableList<LobbyPlayer> = CopyOnWriteArrayList()

    /** Player ids in the same order as [MultiplayerGame.players]. Empty until the game starts. */
    var playerIds: List<String> = emptyList()

    var game: MultiplayerGame? = null

    var feedback: RoomFeedback? = null

    /** A transient message for every device (fetch failure, "add more players", ...). */
    var notice: String? = null

    /** Backs off question fetching after a failure so polling clients don't hammer opentdb. */
    var nextDealAttempt: Instant = Instant.EPOCH

    /**
     * True while one request is out fetching the next question. Every device polls once a second,
     * so without this they would all try to fetch the same question and then queue on [mutex]
     * behind each other's opentdb calls.
     */
    var dealing: Boolean = false

    var version: Long = 0
        private set

    var lastActivity: Instant = Instant.now()
        private set

    val phase: RoomPhase
        get() {
            val current = game ?: return RoomPhase.LOBBY
            return if (current.isOver) RoomPhase.FINISHED else RoomPhase.PLAYING
        }

    /** The id of the device whose turn it is, or null in the lobby or once the game is won. */
    fun currentPlayerId(): String? {
        val current = game?.takeIf { !it.isOver } ?: return null
        return playerIds.getOrNull(current.currentPlayerIndex)
    }

    /** Records a state change: bumps [version] so clients re-render, and marks the room active. */
    fun touch() {
        version++
        lastActivity = Instant.now()
    }

    /** Marks the room active without changing state, so polling alone keeps it from being reaped. */
    fun keepAlive() {
        lastActivity = Instant.now()
    }

    /** Rebuilds the lobby from a finished game so the same players can play again. */
    fun resetToLobby() {
        game = null
        playerIds = emptyList()
        feedback = null
        notice = null
        touch()
    }
}
