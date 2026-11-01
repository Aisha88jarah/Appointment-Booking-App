package com.example.bookings

import com.example.bookings.telegram.MonitorStatus
import com.example.bookings.telegram.PollingEngine
import com.example.bookings.telegram.TelegramApiException
import com.example.bookings.telegram.TelegramGateway
import com.example.bookings.telegram.TgBot
import com.example.bookings.telegram.TgMessage
import com.example.bookings.telegram.TgUpdate
import com.example.bookings.telegram.UpdateStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PollingEngineTest {
    private class FakeStore(var offset: Long = 0) : UpdateStore {
        val saved = mutableListOf<TgUpdate>()
        override suspend fun nextOffset(botId: Long) = offset
        override suspend fun save(updates: List<TgUpdate>, newOffset: Long) { saved += updates; offset = newOffset }
    }

    private class FakeGateway(val script: ArrayDeque<() -> List<TgUpdate>>) : TelegramGateway {
        val offsets = mutableListOf<Long>()
        override suspend fun getMe(token: String) = TgBot(1, "Bot", "school_bot", true, false)
        override suspend fun getUpdates(token: String, offset: Long, timeoutSec: Int): List<TgUpdate> {
            offsets += offset
            return script.removeFirst().invoke()
        }
        override suspend fun deleteWebhook(token: String) {}
        override suspend fun sendMessage(token: String, chatId: Long, text: String) {}
    }

    private fun update(id: Long) = TgUpdate(id, TgMessage(id, 5, 5, null, "A", null, 1, "x", false))
    private val stop = { throw CancellationException("end of script") }

    private suspend fun runEngine(g: FakeGateway, s: FakeStore, status: MutableStateFlow<MonitorStatus>,
                                  pauses: MutableList<MonitorStatus> = mutableListOf(), token: String? = "123456:ABCDEFGHIJKLMNOPQRSTUVWXYZ") {
        val engine = PollingEngine(g, s, { token }, status, pause = { pauses += status.value })
        try { engine.run() } catch (e: CancellationException) { /* end of scripted run */ }
    }

    @Test fun recoversAfterNetworkFailuresAndAdvancesOffset() = runTest {
        val g = FakeGateway(ArrayDeque(listOf({ throw IOException("down") }, { throw IOException("down") }, { listOf(update(10), update(11)) }, stop)))
        val s = FakeStore()
        val status = MutableStateFlow<MonitorStatus>(MonitorStatus.Stopped)
        val pauses = mutableListOf<MonitorStatus>()
        runEngine(g, s, status, pauses)
        assertEquals(listOf(0L, 0L, 0L, 12L), g.offsets)          // same offset while failing, then 11 + 1
        assertEquals(listOf(10L, 11L), s.saved.map { it.updateId })
        assertTrue(pauses.all { it is MonitorStatus.Reconnecting })
        assertEquals(2, pauses.size)
        assertTrue(status.value is MonitorStatus.Connected)
    }

    @Test fun alreadyProcessedUpdatesAreNotSavedAgain() = runTest {
        val g = FakeGateway(ArrayDeque(listOf({ listOf(update(5), update(6)) }, stop)))
        val s = FakeStore(offset = 6)                              // 5 was processed before a restart
        runEngine(g, s, MutableStateFlow(MonitorStatus.Stopped))
        assertEquals(listOf(6L), s.saved.map { it.updateId })
        assertEquals(7L, s.offset)
    }

    @Test fun invalidTokenStopsWithoutRetry() = runTest {
        val g = FakeGateway(ArrayDeque(listOf({ throw TelegramApiException(401, "Unauthorized") })))
        val status = MutableStateFlow<MonitorStatus>(MonitorStatus.Stopped)
        runEngine(g, FakeStore(), status)
        assertEquals(MonitorStatus.InvalidToken, status.value)
        assertEquals(1, g.offsets.size)
    }

    @Test fun webhookConflictIsReportedNotRetried() = runTest {
        val g = FakeGateway(ArrayDeque(listOf({
            throw TelegramApiException(409, "Conflict: can't use getUpdates method while webhook is active; use deleteWebhook to delete the webhook first")
        })))
        val status = MutableStateFlow<MonitorStatus>(MonitorStatus.Stopped)
        runEngine(g, FakeStore(), status)
        assertEquals(MonitorStatus.WebhookConflict, status.value)
    }

    @Test fun anotherPollerConflictIsRetried() = runTest {
        val g = FakeGateway(ArrayDeque(listOf({ throw TelegramApiException(409, "Conflict: terminated by other getUpdates request") }, stop)))
        val pauses = mutableListOf<MonitorStatus>()
        runEngine(g, FakeStore(), MutableStateFlow(MonitorStatus.Stopped), pauses)
        assertEquals(2, g.offsets.size)
        assertEquals(1, pauses.size)
    }

    @Test fun missingTokenStopsImmediately() = runTest {
        val status = MutableStateFlow<MonitorStatus>(MonitorStatus.Stopped)
        runEngine(FakeGateway(ArrayDeque()), FakeStore(), status, token = null)
        assertEquals(MonitorStatus.NoToken, status.value)
    }

    @Test fun backoffGrowsAndIsCapped() {
        assertEquals(2_000L, PollingEngine.backoff(1))
        assertEquals(4_000L, PollingEngine.backoff(2))
        assertEquals(60_000L, PollingEngine.backoff(50))
    }
}
