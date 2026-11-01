package com.example.bookings

import com.example.bookings.telegram.TelegramApi
import com.example.bookings.telegram.TelegramApiException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class TelegramApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: TelegramApi
    private val token = "123456:ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = TelegramApi(baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After fun tearDown() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json; charset=utf-8").setBody(body)

    @Test fun getUpdatesParsesUserIdChatIdAndArabicText() = runBlocking<Unit> {
        server.enqueue(json("""{"ok":true,"result":[{"update_id":900,"message":{"message_id":42,
            "from":{"id":6123456789012345,"is_bot":false,"first_name":"أحمد","username":"ahmad"},
            "chat":{"id":6123456789012345,"type":"private"},"date":1700000000,
            "text":"الاسم: أحمد محمد\nالخدمة: كشف\nالتاريخ: غدا\nالوقت: 10:30 ص"}}]}"""))
        val u = api.getUpdates(token, 0, 0).single()
        val m = u.message!!
        assertEquals(900L, u.updateId)
        assertEquals(6123456789012345L, m.userId)
        assertEquals(6123456789012345L, m.chatId)
        assertEquals(42L, m.messageId)
        assertEquals("ahmad", m.username)
        assertEquals("الاسم: أحمد محمد\nالخدمة: كشف\nالتاريخ: غدا\nالوقت: 10:30 ص", m.text)
        val req = server.takeRequest()
        assertTrue(req.path!!.endsWith("/bot$token/getUpdates"))
        assertTrue(req.body.readUtf8().contains("\"offset\":0"))
    }

    @Test fun groupMessageKeepsChatIdDifferentFromUserId() = runBlocking<Unit> {
        server.enqueue(json("""{"ok":true,"result":[{"update_id":1,"message":{"message_id":2,
            "from":{"id":111,"is_bot":false,"first_name":"S"},"chat":{"id":-1009999,"type":"supergroup"},
            "date":1,"text":"x"}}]}"""))
        val m = api.getUpdates(token, 0, 0).single().message!!
        assertEquals(111L, m.userId)
        assertEquals(-1009999L, m.chatId)
    }

    @Test fun botSendersHaveNoUserId() = runBlocking<Unit> {
        server.enqueue(json("""{"ok":true,"result":[
            {"update_id":1,"message":{"message_id":1,"from":{"id":5,"is_bot":true,"first_name":"B"},"chat":{"id":5},"date":1,"text":"x"}}]}"""))
        assertNull(api.getUpdates(token, 0, 0).single().message!!.userId)
    }

    @Test fun getMeReturnsBotInfo() = runBlocking<Unit> {
        server.enqueue(json("""{"ok":true,"result":{"id":99,"is_bot":true,"first_name":"Clinic","username":"clinic_bot"}}"""))
        val bot = api.getMe(token)
        assertEquals(99L, bot.id)
        assertEquals("clinic_bot", bot.username)
    }

    @Test fun sendMessagePostsChatIdAndExactText() = runBlocking<Unit> {
        server.enqueue(json("""{"ok":true,"result":{"message_id":7}}"""))
        val text = "مرحباً أحمد،\nتم تأكيد موعدك 10:30 {not a placeholder}"
        api.sendMessage(token, 6123456789012345L, text)
        val req = server.takeRequest()
        assertTrue(req.path!!.endsWith("/bot$token/sendMessage"))
        val body = org.json.JSONObject(req.body.readUtf8())
        assertEquals(6123456789012345L, body.getLong("chat_id"))
        assertEquals(text, body.getString("text"))
        assertTrue(!body.has("parse_mode"))
    }

    @Test fun sendMessageErrorsAreReported() = runBlocking<Unit> {
        server.enqueue(json("""{"ok":false,"error_code":403,"description":"Forbidden: bot was blocked by the user"}""").setResponseCode(403))
        try { api.sendMessage(token, 1, "x"); fail() } catch (e: TelegramApiException) { assertEquals(403, e.code) }

        server.enqueue(json("""{"ok":false,"error_code":400,"description":"Bad Request: chat not found"}""").setResponseCode(400))
        try { api.sendMessage(token, 1, "x"); fail() } catch (e: TelegramApiException) { assertEquals(400, e.code) }
    }

    @Test fun errorsMapToTelegramApiException() = runBlocking<Unit> {
        server.enqueue(json("""{"ok":false,"error_code":401,"description":"Unauthorized"}""").setResponseCode(401))
        try { api.getMe(token); fail() } catch (e: TelegramApiException) { assertEquals(401, e.code) }

        server.enqueue(json("""{"ok":false,"error_code":409,"description":"Conflict: can't use getUpdates method while webhook is active"}""").setResponseCode(409))
        try { api.getUpdates(token, 0, 0); fail() } catch (e: TelegramApiException) { assertTrue(e.isWebhookConflict) }

        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad Gateway</html>"))
        try { api.getMe(token); fail() } catch (e: TelegramApiException) { assertEquals(502, e.code) }
    }
}
