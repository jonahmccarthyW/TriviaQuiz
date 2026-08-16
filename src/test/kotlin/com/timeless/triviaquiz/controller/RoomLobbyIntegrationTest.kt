package com.timeless.triviaquiz.controller

import com.timeless.triviaquiz.service.TriviaCategory
import com.timeless.triviaquiz.service.TriviaQuestionService
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.ClientHttpResponse
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.ResponseErrorHandler
import org.springframework.web.client.RestTemplate
import org.springframework.web.reactive.function.client.WebClient
import java.net.HttpURLConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The join flow over real HTTP, with several "devices" — the thing the hotseat game never had to
 * do. Each device is a separate cookie jar, which is what makes them separate players.
 *
 * Stops short of starting a game: dealing a question would call opentdb, and this test is meant to
 * pass with the network unplugged. Category lookup is stubbed for the same reason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RoomLobbyIntegrationTest.StubbedCategories::class)
class RoomLobbyIntegrationTest {

    @TestConfiguration
    class StubbedCategories {
        @Bean
        @Primary
        fun stubbedTriviaQuestionService(webClient: WebClient): TriviaQuestionService =
            object : TriviaQuestionService(webClient) {
                override suspend fun getCategories(): List<TriviaCategory> =
                    (1..10).map { TriviaCategory(it, "Category $it") }
            }
    }

    @LocalServerPort
    private var port: Int = 0

    /**
     * One browser: its own session cookie, which is what makes it a distinct player. Redirects are
     * not followed (so a join can be asserted as a redirect rather than silently re-POSTing to the
     * target) and error statuses are returned rather than thrown, since 404 and 409 are outcomes
     * this test cares about.
     */
    private inner class Device {
        private var cookie: String? = null

        private val http = RestTemplate(
            object : SimpleClientHttpRequestFactory() {
                override fun prepareConnection(connection: HttpURLConnection, httpMethod: String) {
                    super.prepareConnection(connection, httpMethod)
                    connection.instanceFollowRedirects = false
                }
            }
        ).apply {
            errorHandler = object : ResponseErrorHandler {
                override fun hasError(response: ClientHttpResponse) = false
                override fun handleError(response: ClientHttpResponse) = Unit
            }
        }

        fun get(path: String): ResponseEntity<String> =
            exchange(path, HttpMethod.GET, HttpEntity<Any>(headers()))

        fun postForm(path: String, form: Map<String, List<String>>): ResponseEntity<String> {
            val headers = headers()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            val body = LinkedMultiValueMap<String, String>()
            form.forEach { (key, values) -> values.forEach { body.add(key, it) } }
            return exchange(path, HttpMethod.POST, HttpEntity(body, headers))
        }

        fun postJson(path: String, json: String): ResponseEntity<String> {
            val headers = headers()
            headers.contentType = MediaType.APPLICATION_JSON
            return exchange(path, HttpMethod.POST, HttpEntity(json, headers))
        }

        private fun headers(): HttpHeaders = HttpHeaders().apply {
            cookie?.let { add(HttpHeaders.COOKIE, it) }
        }

        private fun exchange(path: String, method: HttpMethod, entity: HttpEntity<*>): ResponseEntity<String> {
            val response = http.exchange(
                "http://localhost:$port$path", method, entity, String::class.java
            )
            response.headers[HttpHeaders.SET_COOKIE]
                ?.firstOrNull { it.startsWith("JSESSIONID") }
                ?.let { cookie = it.substringBefore(';') }
            return response
        }
    }

    /** Opens the host page and reads the room code back out of it. */
    private fun hostNewRoom(host: Device): String {
        val body = host.get("/host").body.orEmpty()
        val code = Regex("Room code <strong>([A-Z0-9]{4})</strong>").find(body)?.groupValues?.get(1)
        assertNotNull(code, "host page should show a room code")
        return code
    }

    private fun join(device: Device, code: String, name: String, categories: List<Int>) {
        val response = device.postForm(
            "/j/$code",
            mapOf("name" to listOf(name), "categories" to categories.map { it.toString() })
        )
        assertTrue(
            response.statusCode.is3xxRedirection,
            "joining should redirect to /play but was ${response.statusCode}: ${response.body}"
        )
    }

    @Test
    fun `two devices join the same room and each sees itself in the roster`() {
        val host = Device()
        val code = hostNewRoom(host)

        val ann = Device()
        val bob = Device()
        join(ann, code, "Ann", listOf(1, 2, 3, 4, 5, 6))
        join(bob, code, "Bob", listOf(1, 2, 3, 4, 5, 7))

        val annState = ann.get("/api/room/$code/state").body.orEmpty()
        assertTrue(annState.contains("\"you\":\"Ann\""), "Ann's view should name her: $annState")
        assertTrue(annState.contains("\"phase\":\"lobby\""), annState)
        assertTrue(annState.contains("\"canStart\":true"), annState)
        assertTrue(annState.contains("\"name\":\"Ann\""), annState)
        assertTrue(annState.contains("\"name\":\"Bob\""), annState)

        val bobState = bob.get("/api/room/$code/state").body.orEmpty()
        assertTrue(bobState.contains("\"you\":\"Bob\""), "Bob's view should name him: $bobState")
    }

    @Test
    fun `the host is a spectator, not a player`() {
        val host = Device()
        val code = hostNewRoom(host)
        join(Device(), code, "Ann", listOf(1, 2, 3, 4, 5, 6))

        val state = host.get("/api/room/$code/state").body.orEmpty()

        assertTrue(state.contains("\"you\":null"), "host is nobody in the game: $state")
        assertTrue(state.contains("\"yourTurn\":false"), state)
    }

    @Test
    fun `the join form renders on a first visit`() {
        val code = hostNewRoom(Device())

        val response = Device().get("/j/$code")

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body.orEmpty()
        assertTrue(body.contains("Join room $code"), body)
        assertTrue(body.contains("""name="categories""""), "should offer categories to pick: $body")
    }

    @Test
    fun `the game screen renders once joined`() {
        val code = hostNewRoom(Device())
        val ann = Device()
        join(ann, code, "Ann", listOf(1, 2, 3, 4, 5, 6))

        val response = ann.get("/play")

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body.orEmpty().contains("startRoom("), response.body.orEmpty())
    }

    @Test
    fun `a device that has not joined is sent to the join form`() {
        val code = hostNewRoom(Device())

        val response = Device().get("/play")

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body.orEmpty().contains("not in a game"), response.body.orEmpty())
        assertTrue(code.isNotEmpty())
    }

    @Test
    fun `joining a room that does not exist does not blow up`() {
        val response = Device().get("/j/ZZZZ")

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body.orEmpty().contains("No room with that code"), response.body.orEmpty())
    }

    @Test
    fun `a device cannot answer when it is not its turn`() {
        val host = Device()
        val code = hostNewRoom(host)
        val ann = Device()
        join(ann, code, "Ann", listOf(1, 2, 3, 4, 5, 6))

        // Still in the lobby: there is no question, so nobody may answer.
        val response = ann.postJson("/api/room/$code/answer", """{"version":1,"answer":"anything"}""")

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
    }

    @Test
    fun `polling a closed room reports it gone`() {
        val response = Device().get("/api/room/ZZZZ/state")

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }
}
