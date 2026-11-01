package com.example.bookings.telegram

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

data class TgBot(
    val id: Long,
    val firstName: String,
    val username: String?,
    val canJoinGroups: Boolean?,
    val canReadAllGroupMessages: Boolean?,
)

data class TgMessage(
    val messageId: Long,
    val chatId: Long,            // message.chat.id
    val userId: Long?,           // message.from.id (null when absent or sender is a bot)
    val username: String?,
    val firstName: String?,
    val lastName: String?,
    val date: Long,              // unix seconds
    val text: String?,           // text, or caption when the message has no text
    val edited: Boolean,
)

data class TgUpdate(val updateId: Long, val message: TgMessage?)

class TelegramApiException(
    val code: Int,
    description: String,
    val retryAfter: Int? = null,
) : Exception(description) {
    val isWebhookConflict: Boolean get() = code == 409 && message.orEmpty().contains("webhook", ignoreCase = true)
}

interface TelegramGateway {
    suspend fun getMe(token: String): TgBot
    suspend fun getUpdates(token: String, offset: Long, timeoutSec: Int): List<TgUpdate>
    suspend fun deleteWebhook(token: String)
    suspend fun sendMessage(token: String, chatId: Long, text: String)
}

class TelegramApi(
    private val baseUrl: String = "https://api.telegram.org",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)   // must exceed the 30 s long-poll timeout
        .build(),
) : TelegramGateway {

    override suspend fun getMe(token: String): TgBot {
        val r = call(token, "getMe", JSONObject()).getJSONObject("result")
        return TgBot(
            id = r.getLong("id"),
            firstName = r.optString("first_name"),
            username = r.str("username"),
            canJoinGroups = r.boolOrNull("can_join_groups"),
            canReadAllGroupMessages = r.boolOrNull("can_read_all_group_messages"),
        )
    }

    override suspend fun getUpdates(token: String, offset: Long, timeoutSec: Int): List<TgUpdate> {
        val body = JSONObject()
            .put("offset", offset)
            .put("timeout", timeoutSec)
            .put("allowed_updates", JSONArray(listOf("message", "edited_message")))
        val arr = call(token, "getUpdates", body).getJSONArray("result")
        return parseUpdates(arr)
    }

    override suspend fun deleteWebhook(token: String) {
        call(token, "deleteWebhook", JSONObject().put("drop_pending_updates", false))
    }

    /** Plain-text message (no parse_mode) so nothing typed by the admin can be misread as markup. */
    override suspend fun sendMessage(token: String, chatId: Long, text: String) {
        call(token, "sendMessage", JSONObject().put("chat_id", chatId).put("text", text))
    }

    private suspend fun call(token: String, method: String, params: JSONObject): JSONObject {
        val req = Request.Builder()
            .url("$baseUrl/bot$token/$method")
            .post(params.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return client.newCall(req).await().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw TelegramApiException(resp.code, "Invalid response from Telegram")
            }
            if (!json.optBoolean("ok", false)) {
                val retry = json.optJSONObject("parameters")?.let { if (it.has("retry_after")) it.getInt("retry_after") else null }
                throw TelegramApiException(json.optInt("error_code", resp.code), json.optString("description"), retry)
            }
            json
        }
    }

    companion object {
        fun parseUpdates(arr: JSONArray): List<TgUpdate> =
            (0 until arr.length()).map { parseUpdate(arr.getJSONObject(it)) }

        fun parseUpdate(o: JSONObject): TgUpdate {
            val id = o.getLong("update_id")
            val edited = !o.has("message") && o.has("edited_message")
            val m = o.optJSONObject("message") ?: o.optJSONObject("edited_message")
            val msg = m?.let { runCatching { parseMessage(it, edited) }.getOrNull() }
            return TgUpdate(id, msg)
        }

        private fun parseMessage(m: JSONObject, edited: Boolean): TgMessage {
            val chat = m.getJSONObject("chat")
            val from = m.optJSONObject("from")
            val isBot = from?.boolOrNull("is_bot") ?: false
            val userId = if (from != null && from.has("id") && !isBot) from.getLong("id") else null
            return TgMessage(
                messageId = m.getLong("message_id"),
                chatId = chat.getLong("id"),
                userId = userId,
                username = from?.str("username"),
                firstName = from?.str("first_name"),
                lastName = from?.str("last_name"),
                date = m.optLong("date", 0L),
                text = m.str("text") ?: m.str("caption"),
                edited = edited,
            )
        }
    }
}

private fun JSONObject.str(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

private fun JSONObject.boolOrNull(key: String): Boolean? =
    if (has(key) && !isNull(key)) getBoolean(key) else null

/** Suspends until the call completes; cancelling the coroutine cancels the HTTP call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}
