package com.example

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "connection_history",
    indices = [Index(value = ["timestamp"])]
)
data class ConnectionHistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val connectionType: String, // "Wi-Fi", "Mobile", "Ethernet", or "Offline"
    val isConnected: Boolean,
    val identifier: String, // SSID or Operator name
    val networkType: String, // Band frequency or Cellular standard (e.g. 5G/LTE)
    val signalStrength: String // RSSI or dBm
)

@Dao
interface ConnectionHistoryDao {
    @Query("SELECT * FROM connection_history ORDER BY timestamp DESC LIMIT 200")
    fun getAllHistory(): Flow<List<ConnectionHistoryEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ConnectionHistoryEntry)

    @Query("DELETE FROM connection_history")
    suspend fun clearHistory()
}

@Database(entities = [ConnectionHistoryEntry::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): ConnectionHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "connection_history_db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class HistoryRepository(private val database: AppDatabase) {
    val allHistory: Flow<List<ConnectionHistoryEntry>> = database.historyDao().getAllHistory()

    suspend fun insert(entry: ConnectionHistoryEntry) {
        try {
            database.historyDao().insert(entry)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun clearAll() {
        try {
            database.historyDao().clearHistory()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
