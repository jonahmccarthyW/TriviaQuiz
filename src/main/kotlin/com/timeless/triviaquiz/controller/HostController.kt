package com.timeless.triviaquiz.controller

import com.timeless.triviaquiz.game.GameRoom
import com.timeless.triviaquiz.game.MpPlayer
import com.timeless.triviaquiz.game.MultiplayerGame
import com.timeless.triviaquiz.game.RoomPhase
import com.timeless.triviaquiz.service.GameRoomService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.ui.set
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The host screen, opened on the machine running the server. It creates a room, shows the join
 * address for everyone else to type into their phone, and starts the game once players have joined.
 * The host is a spectator: they never answer, so the board here shows standings and the question
 * but no answer buttons.
 */
@Controller
class HostController(private val gameRoomService: GameRoomService) {

    /** Shows the host screen, creating a room the first time this browser asks for one. */
    @GetMapping("/host")
    fun host(model: Model, session: HttpSession, request: HttpServletRequest): String {
        val room = hostedRoom(session) ?: gameRoomService.create().also {
            session.setAttribute(RoomSession.HOSTED_ROOM_CODE, it.code)
        }

        model["title"] = "Host a game"
        model["code"] = room.code
        model["joinUrls"] = joinUrls(request, room.code)
        model["minPlayers"] = GameRoomService.MIN_PLAYERS
        return "host"
    }

    /** Starts the game with everyone currently in the lobby, in the order they joined. */
    @PostMapping("/host/start")
    suspend fun start(session: HttpSession): String {
        val room = hostedRoom(session) ?: return "redirect:/host"

        room.mutex.withLock {
            if (room.phase != RoomPhase.LOBBY) return "redirect:/host"

            if (room.lobby.size < GameRoomService.MIN_PLAYERS) {
                room.notice = "Need at least ${GameRoomService.MIN_PLAYERS} players to start."
                room.touch()
                return "redirect:/host"
            }

            room.playerIds = room.lobby.map { it.playerId }
            room.game = MultiplayerGame(room.lobby.map { MpPlayer(name = it.name, categories = it.categories) })
            room.feedback = null
            room.notice = null
            room.touch()
        }
        return "redirect:/host"
    }

    /**
     * "Play again": drops the finished game but keeps the lobby, so the same devices keep their
     * names and category picks and can start another round without rejoining.
     */
    @PostMapping("/host/reset")
    suspend fun reset(session: HttpSession): String {
        val room = hostedRoom(session) ?: return "redirect:/host"
        room.mutex.withLock { room.resetToLobby() }
        return "redirect:/host"
    }

    /** Closes the room. Players' devices see "room closed" on their next poll. */
    @PostMapping("/host/close")
    fun close(session: HttpSession): String {
        gameRoomService.close(session.getAttribute(RoomSession.HOSTED_ROOM_CODE) as? String)
        session.removeAttribute(RoomSession.HOSTED_ROOM_CODE)
        return "redirect:/"
    }

    private fun hostedRoom(session: HttpSession): GameRoom? =
        gameRoomService.find(session.getAttribute(RoomSession.HOSTED_ROOM_CODE) as? String)

    /**
     * The addresses players should type in. The host loads this page from the same server, so the
     * port on the incoming request is the right one; only the host part needs replacing, because
     * "localhost" means the phone itself. A laptop on Wi-Fi usually has one site-local IPv4, but
     * VPNs and Docker bridges can add more, so all candidates are listed rather than guessed at.
     */
    private fun joinUrls(request: HttpServletRequest, code: String): List<String> {
        val port = request.serverPort
        return lanAddresses().map { "http://$it:$port/j/$code" }
    }

    private fun lanAddresses(): List<String> =
        runCatching {
            NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress }
                .distinct()
                .toList()
        }.getOrDefault(emptyList())
}
