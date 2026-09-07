package io.github.nobu0601.icocaautocharge.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [ChargeHistoryEntity::class, BalanceSampleEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chargeHistoryDao(): ChargeHistoryDao
    abstract fun balanceSampleDao(): BalanceSampleDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "icoca_auto_charge.db",
                ).build().also { instance = it }
            }
    }
}
