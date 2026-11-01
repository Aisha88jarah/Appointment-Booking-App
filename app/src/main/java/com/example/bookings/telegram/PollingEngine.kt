package com.example.bookings.telegram

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow

sealed interface MonitorStatus {
    data object Stopped : MonitorStatus
    data object Starting : MonitorStatus
    data class Connected(val botUsername: String?) : MonitorStatus
    data class Reconnecting(val reason: String) : MonitorStatus
    data object InvalidToken : MonitorStatus
    data object WebhookConflict : MonitorStatus
    data object NoToken : MonitorStatus
}

/** Persists a batch of updates together with the new offset (atomically). */
interface UpdateStore {
    /** Returns the stored offset for this bot; resets to 0 when the bot changed. */
    suspend fun nextOffset(botId: Long): Long
    suspend fun save(updates: List<TgUpdate>, newOffset: Long)
}

/**
 * getUpdates long-polling loop. Retries network/API failures with exponential backoff,
 * stops on invalid token or an active webhook (the user must resolve those).
 */
class PollingEngine(
    private val gateway: TelegramGateway,
    private val store: UpdateStore,
    private val tokenProvider: () -> String?,
    private val status: MutableStateFlow<MonitorStatus>,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val pollTimeoutSec: Int = 30,
) {
    suspend fun run() {
        status.value = MonitorStatus.Starting
        var failures = 0
        var ready = false
        var offset = 0L
        var botUsername: String? = null

        while (true) {
            currentCoroutineContext().ensureActive()
            val token = tokenProvider()
            if (token.isNullOrBlank()) {
                status.value = MonitorStatus.NoToken
                return
            }
            try {
                if (!ready) {
                    val bot = gateway.getMe(token)
                    botUsername = bot.username
                    offset = store.nextOffset(bot.id)
                    ready = true
                    status.value = MonitorStatus.Connected(botUsername)
                }
                val current = offset
                val updates = gateway.getUpdates(token, current, pollTimeoutSec)
                status.value = MonitorStatus.Connected(botUsername)
                failures = 0
                if (updates.isNotEmpty()) {
                    val fresh = updates.filter { it.updateId >= current }          // never reprocess
                    val next = maxOf(current, updates.maxOf { it.updateId } + 1)
                    store.save(fresh, next)                                         // data + offset in one transaction
                    offset = next
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TelegramApiException) {
                when {
                    e.code == 401 || e.code == 404 -> {
                        status.value = MonitorStatus.InvalidToken
                        return
                    }
                    e.isWebhookConflict -> {
                        status.value = MonitorStatus.WebhookConflict
                        return
                    }
                    e.code == 429 -> {
                        status.value = MonitorStatus.Reconnecting("rate limit")
                        pause(((e.retryAfter ?: 5) + 1) * 1000L)
                    }
                    else -> {
                        failures++
                        status.value = MonitorStatus.Reconnecting("Telegram ${e.code}")
                        pause(backoff(failures))
                    }
                }
            } catch (e: IOException) {
                failures++
                status.value = MonitorStatus.Reconnecting("network")
                pause(backoff(failures))
            } catch (e: Exception) {
                failures++
                status.value = MonitorStatus.Reconnecting(e.javaClass.simpleName)   // never include e.message (could echo a URL)
                pause(backoff(failures))
            }
        }
    }

    companion object {
        fun backoff(failures: Int): Long = minOf(60_000L, 2_000L * (1L shl minOf(failures - 1, 5)))
    }
}
