package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ProjectEntity::class, PresetEntity::class],
    version = 1,
    exportSchema = false
)
abstract class StudioDatabase : RoomDatabase() {
    abstract fun studioDao(): StudioDao

    companion object {
        @Volatile
        private var INSTANCE: StudioDatabase? = null

        fun getInstance(context: Context): StudioDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StudioDatabase::class.java,
                    "voice_pro_studio.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
