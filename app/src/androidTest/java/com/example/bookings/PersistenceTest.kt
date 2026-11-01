package com.example.bookings

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.bookings.data.AppDatabase
import com.example.bookings.data.ApptStatus
import com.example.bookings.data.RoomUpdateStore
import com.example.bookings.telegram.TgMessage
import com.example.bookings.telegram.TgUpdate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Simulates an app restart by closing and re-opening the on-disk database. */
@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val name = "persistence-test.db"

    private fun open() = Room.databaseBuilder(ctx, AppDatabase::class.java, name).build()

    private fun update(id: Long, msgId: Long, text: String) =
        TgUpdate(id, TgMessage(msgId, 4242, 777, "ann", "Ann", null, 1_793_000_000, text, false))

    @Test fun requestIsSavedAndSurvivesRestart() = runBlocking<Unit> {
        ctx.deleteDatabase(name)
        var created = 0
        var db = open()
        var store = RoomUpdateStore(db) { created += it }
        assertEquals(0L, store.nextOffset(botId = 1))
        store.save(listOf(update(100, 1, "Name: Ann Lee\nDate: 2026-11-05\nTime: 10:30")), newOffset = 101)
        assertEquals(1, created)
        db.close()

        db = open()                                   // "restart"
        store = RoomUpdateStore(db)
        assertEquals(101L, store.nextOffset(botId = 1))
        val a = db.appointments().byMessage(4242, 1).single()
        assertEquals(777L, a.telegramUserId)
        assertEquals(4242L, a.chatId)                 // chat id is NOT the user id
        assertEquals("Ann Lee", a.clientName)
        assertEquals("2026-11-05", a.reqDate)
        assertEquals(ApptStatus.PENDING, a.status)

        // the same update delivered again must not create a second request
        store.save(listOf(update(100, 1, "Name: Ann Lee\nDate: 2026-11-05\nTime: 10:30")), newOffset = 101)
        assertEquals(1, db.appointments().byMessage(4242, 1).size)

        // an edited message refreshes the still-pending request
        store.save(listOf(update(101, 1, "Name: Ann Lee\nDate: 2026-11-06\nTime: 11:00")), newOffset = 102)
        assertEquals("2026-11-06", db.appointments().byMessage(4242, 1).single().reqDate)

        // once the admin decided, later re-deliveries never overwrite it
        val handled = db.appointments().byMessage(4242, 1).single()
        db.appointments().update(handled.copy(status = ApptStatus.CONFIRMED, slotStart = 1L, slotEnd = 2L))
        store.save(listOf(update(102, 1, "Name: Someone Else")), newOffset = 103)
        assertEquals("Ann Lee", db.appointments().byMessage(4242, 1).single().clientName)

        assertEquals(0L, store.nextOffset(botId = 2))   // another bot resets the offset
        db.close()
        ctx.deleteDatabase(name)
    }

    @Test fun commandsAndEmptyMessagesCreateNothing() = runBlocking<Unit> {
        ctx.deleteDatabase(name)
        val db = open()
        val store = RoomUpdateStore(db)
        store.nextOffset(botId = 1)
        store.save(listOf(update(1, 1, "/start"), update(2, 2, "   ")), newOffset = 3)
        assertEquals(0, db.appointments().byMessage(4242, 1).size + db.appointments().byMessage(4242, 2).size)
        db.close()
        ctx.deleteDatabase(name)
    }
}
