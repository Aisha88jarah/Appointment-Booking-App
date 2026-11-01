package com.example.bookings.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

enum class ApptStatus { PENDING, CONFIRMED, DECLINED, CANCELLED, DISMISSED }

/**
 * One appointment request from a client. telegramUserId (message.from.id) and chatId
 * (message.chat.id) are separate identifiers. A request becomes CONFIRMED only after the
 * administrator reviewed and sent the confirmation message.
 */
@Entity(
    tableName = "appointments",
    indices = [Index("telegramUserId"), Index(value = ["chatId", "messageId"]), Index("slotStart")]
)
data class Appointment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val telegramUserId: Long,
    val chatId: Long,
    val username: String?,
    val firstName: String?,
    val lastName: String?,
    val clientName: String?,      // as written by the client
    val phone: String?,
    val serviceId: Long?,         // matched automatically or chosen by the admin
    val serviceText: String?,     // as written by the client
    val serviceName: String?,     // name of the chosen service (snapshot)
    val durationMin: Int?,        // duration used for the confirmed slot
    val reqDate: String?,         // understood date, ISO yyyy-MM-dd
    val reqTime: String?,         // understood time, HH:mm
    val reqText: String?,         // date/time exactly as the client wrote it
    val notes: String?,
    val slotStart: Long?,         // epoch millis, set when confirmed
    val slotEnd: Long?,
    val originalMessage: String,
    val messageId: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val status: ApptStatus,
    val lastMessageText: String?, // last message the admin sent to the client
    val lastMessageAt: Long?,
) {
    fun displayName(): String =
        clientName?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(firstName, lastName).joinToString(" ").ifBlank {
                username?.let { "@$it" } ?: telegramUserId.toString()
            }
}

@Entity(tableName = "services")
data class Service(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val durationMin: Int,
    val active: Boolean = true,
)

/** Single row (id = 1) with everything the administrator can adjust. */
@Entity(tableName = "settings")
data class AppSettings(
    @PrimaryKey val id: Int = 1,
    val businessName: String,
    val address: String,
    val stepMin: Int,              // how often an appointment may start (grid)
    val bufferMin: Int,            // gap kept between appointments
    val defaultDurationMin: Int,   // used when no service is selected
    val packTight: Boolean,        // also offer the first free time right after an appointment (+gap)
    val workWeek: String,          // see WorkWeek.encode
    val confirmTpl: String,
    val declineTpl: String,
    val rescheduleTpl: String,
    val cancelTpl: String,
)

@Entity(tableName = "bot_state")
data class BotState(
    @PrimaryKey val id: Int = 1,
    val botId: Long,
    val nextOffset: Long,
)

@Dao
interface AppointmentDao {
    @Query("SELECT * FROM appointments ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Appointment>>

    @Query("SELECT * FROM appointments WHERE id = :id")
    suspend fun byId(id: Long): Appointment?

    @Query("SELECT * FROM appointments WHERE chatId = :chatId AND messageId = :messageId")
    suspend fun byMessage(chatId: Long, messageId: Long): List<Appointment>

    @Query("SELECT * FROM appointments WHERE status = 'CONFIRMED' AND slotStart IS NOT NULL AND slotEnd > :from AND slotStart < :to")
    suspend fun confirmedBetween(from: Long, to: Long): List<Appointment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(a: Appointment): Long

    @Update
    suspend fun update(a: Appointment)

    @Query("DELETE FROM appointments WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ServiceDao {
    @Query("SELECT * FROM services ORDER BY name")
    fun observeAll(): Flow<List<Service>>

    @Query("SELECT * FROM services WHERE active = 1 ORDER BY name")
    suspend fun allActive(): List<Service>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: Service): Long

    @Query("DELETE FROM services WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 1")
    fun observe(): Flow<AppSettings?>

    @Query("SELECT * FROM settings WHERE id = 1")
    suspend fun get(): AppSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(s: AppSettings)
}

@Dao
interface BotStateDao {
    @Query("SELECT * FROM bot_state WHERE id = 1")
    suspend fun get(): BotState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(s: BotState)
}

@Database(
    entities = [Appointment::class, Service::class, AppSettings::class, BotState::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appointments(): AppointmentDao
    abstract fun services(): ServiceDao
    abstract fun settings(): SettingsDao
    abstract fun botState(): BotStateDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "bookings.db").build()
    }
}
