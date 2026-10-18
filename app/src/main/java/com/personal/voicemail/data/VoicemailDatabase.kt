package com.personal.voicemail.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [VoicemailEntity::class], version = 1, exportSchema = false)
abstract class VoicemailDatabase : RoomDatabase() {

    abstract fun voicemailDao(): VoicemailDao

    companion object {
        @Volatile
        private var INSTANCE: VoicemailDatabase? = null

        fun getInstance(context: Context): VoicemailDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    VoicemailDatabase::class.java,
                    "live_voicemail.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
