package com.handykit.app.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 普通数据区。安全笔记不在此库，使用独立的加密库。
 * 版本化：升级失败不得清空数据，因此提供显式迁移而非 fallbackToDestructiveMigration。
 */
@Database(
    entities = [
        Vehicle::class, FuelRecord::class,
        ScaleMember::class, Measurement::class,
        AiRole::class, AiProvider::class, AiSession::class, AiMessage::class, AiAttachment::class,
        MusicTrack::class, MusicPlaylist::class, PlaylistTrack::class, MusicLyric::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vehicleDao(): VehicleDao
    abstract fun fuelDao(): FuelDao
    abstract fun scaleDao(): ScaleDao
    abstract fun aiDao(): AiDao
    abstract fun musicDao(): MusicDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "handykit.db",
            ).build().also { instance = it }
        }
    }
}
