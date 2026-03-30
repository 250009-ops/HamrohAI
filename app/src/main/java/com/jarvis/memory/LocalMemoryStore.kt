package com.jarvis.memory

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

class LocalMemoryStore private constructor(private val dao: MemoryDao) {

    suspend fun upsertProfileFact(key: String, value: String) {
        dao.upsertFact(ProfileFact(key = key, value = value, updatedAt = System.currentTimeMillis()))
    }

    suspend fun addSessionSummary(summary: String) {
        dao.insertSummary(SessionSummary(summary = summary, createdAt = System.currentTimeMillis()))
    }

    suspend fun recentSummaries(limit: Int = 8): List<SessionSummary> = dao.recentSummaries(limit)

    suspend fun profileFacts(): List<ProfileFact> = dao.getAllFacts()

    suspend fun clearAll() {
        dao.clearFacts()
        dao.clearSummaries()
    }

    suspend fun exportAsJson(): String {
        val facts = dao.getAllFacts().joinToString(
            prefix = "[",
            postfix = "]"
        ) { """{"key":"${it.key}","value":"${it.value}","updatedAt":${it.updatedAt}}""" }
        val summaries = dao.recentSummaries(100).joinToString(
            prefix = "[",
            postfix = "]"
        ) { """{"summary":"${it.summary}","createdAt":${it.createdAt}}""" }
        return """{"facts":$facts,"summaries":$summaries}"""
    }

    companion object {
        @Volatile
        private var instance: LocalMemoryStore? = null

        fun get(context: Context): LocalMemoryStore {
            return instance ?: synchronized(this) {
                instance ?: buildStore(context.applicationContext).also { instance = it }
            }
        }

        private fun buildStore(context: Context): LocalMemoryStore {
            val db = Room.databaseBuilder(context, JarvisDatabase::class.java, "jarvis-memory.db")
                .fallbackToDestructiveMigration()
                .build()
            return LocalMemoryStore(db.memoryDao())
        }

        fun rankSummariesByKeyword(query: String, summaries: List<SessionSummary>): List<SessionSummary> {
            val normalizedQuery = query.lowercase().trim()
            if (normalizedQuery.isBlank()) return summaries
            return summaries.sortedByDescending { summary ->
                summary.summary.lowercase().split(" ").count { it.contains(normalizedQuery) }
            }
        }
    }
}

@Entity(tableName = "profile_facts")
data class ProfileFact(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long
)

@Entity(tableName = "session_summaries")
data class SessionSummary(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val summary: String,
    val createdAt: Long
)

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFact(fact: ProfileFact)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSummary(summary: SessionSummary)

    @Query("SELECT * FROM profile_facts ORDER BY updatedAt DESC")
    suspend fun getAllFacts(): List<ProfileFact>

    @Query("SELECT * FROM session_summaries ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentSummaries(limit: Int): List<SessionSummary>

    @Query("DELETE FROM profile_facts")
    suspend fun clearFacts()

    @Query("DELETE FROM session_summaries")
    suspend fun clearSummaries()
}

@Database(entities = [ProfileFact::class, SessionSummary::class], version = 1, exportSchema = false)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
}
