package com.timeless.triviaquiz.service

import com.timeless.triviaquiz.game.DIFFICULTIES
import com.timeless.triviaquiz.game.MultiplayerGame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Puts the next question in front of the current player of a [MultiplayerGame]. Shared by the
 * hotseat game and the networked rooms, which deal questions identically — only where the game
 * state lives differs.
 */
@Service
class QuestionDealer(private val triviaQuestionService: TriviaQuestionService) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fetches a question for the current player at the current difficulty, trying each remaining
     * category until one yields a question. If none do, the turn ends and the next player is tried;
     * returns false only if no player could be given a question (bounded to avoid looping forever).
     */
    suspend fun dealNextQuestion(game: MultiplayerGame): Boolean {
        repeat(game.players.size) {
            for (category in game.currentPlayer.remainingCategories().shuffled()) {
                val question = try {
                    triviaQuestionService.getBufferedQuestion(category.id, game.currentDifficulty)
                } catch (e: Exception) {
                    // opentdb refused the call (rate limit, or it's down). Give up on this whole
                    // attempt rather than trying the other categories: they would queue behind the
                    // same limit and turn one bad call into a long stall. The caller backs off and
                    // retries, and an exception here must never reach the browser as a 500.
                    logger.warn("Couldn't fetch a question for category {}: {}", category.id, e.toString())
                    return false
                }
                if (question != null) {
                    game.currentQuestion = question
                    game.currentCategoryId = category.id
                    return true
                }
            }
            // Nothing available for this player at this difficulty — pass the turn on.
            game.endTurn()
        }
        return false
    }

    /**
     * Fire-and-forget top-up of the buffer for the difficulty this player faces next, so opentdb is
     * called while they are still reading the current question instead of after they answer it.
     * This matters more with several devices than it did in hotseat: everyone is watching the same
     * pause. Failures are ignored — [dealNextQuestion] still fetches on demand.
     */
    fun prefetchAhead(game: MultiplayerGame) {
        val nextStep = game.currentStep + 1
        if (nextStep > DIFFICULTIES.lastIndex) return
        val difficulty = DIFFICULTIES[nextStep]
        val category = game.currentPlayer.remainingCategories()
            .firstOrNull { it.id != game.currentCategoryId } ?: return

        prefetchScope.launch {
            runCatching { triviaQuestionService.prefetch(category.id, difficulty) }
        }
    }
}
