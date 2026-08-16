package com.timeless.triviaquiz.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Room bookkeeping around the [MultiplayerGame] state machine: phases, and the mapping from a
 * device's player id back to its seat in the game. Rules of play are covered by [MultiplayerGameTest].
 */
class GameRoomTest {

    private fun categories(vararg ids: Int) = ids.map { MpCategory(it, "Category $it") }

    private fun roomWithLobby(): GameRoom = GameRoom("ABCD").apply {
        lobby.add(LobbyPlayer("id-a", "Ann", categories(1, 2, 3, 4, 5, 6)))
        lobby.add(LobbyPlayer("id-b", "Bob", categories(7, 8, 9, 10, 11, 12)))
    }

    private fun startedRoom(): GameRoom = roomWithLobby().apply {
        playerIds = lobby.map { it.playerId }
        game = MultiplayerGame(lobby.map { MpPlayer(it.name, it.categories) })
    }

    @Test
    fun `a new room is in the lobby phase with nobody up`() {
        val room = GameRoom("ABCD")

        assertEquals(RoomPhase.LOBBY, room.phase)
        assertNull(room.currentPlayerId())
    }

    @Test
    fun `starting a game moves the room to playing and puts the first joiner up`() {
        val room = startedRoom()

        assertEquals(RoomPhase.PLAYING, room.phase)
        assertEquals("id-a", room.currentPlayerId())
    }

    @Test
    fun `the current player id follows the game's turn order`() {
        val room = startedRoom()

        room.game!!.endTurn()

        assertEquals("id-b", room.currentPlayerId())
    }

    @Test
    fun `a won game is finished and nobody is up`() {
        val room = startedRoom()
        val game = room.game!!

        // Clear Ann's six categories: each correct answer clears the one the question came from.
        game.currentPlayer.categories.forEach { category ->
            game.currentCategoryId = category.id
            game.applyAnswer(correct = true)
        }

        assertEquals(RoomPhase.FINISHED, room.phase)
        assertEquals("Ann", game.winnerName)
        assertNull(room.currentPlayerId())
    }

    @Test
    fun `touch bumps the version so clients know to redraw`() {
        val room = GameRoom("ABCD")
        val before = room.version

        room.touch()

        assertNotEquals(before, room.version)
    }

    @Test
    fun `keepAlive marks activity without bumping the version`() {
        val room = GameRoom("ABCD")
        val version = room.version

        room.keepAlive()

        assertEquals(version, room.version)
    }

    @Test
    fun `resetting keeps the lobby so the same devices can play again`() {
        val room = startedRoom()

        room.resetToLobby()

        assertEquals(RoomPhase.LOBBY, room.phase)
        assertEquals(listOf("Ann", "Bob"), room.lobby.map { it.name })
        assertTrue(room.playerIds.isEmpty())
        assertNull(room.currentPlayerId())
    }
}
