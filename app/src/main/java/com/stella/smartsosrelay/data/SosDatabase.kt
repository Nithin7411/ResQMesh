package com.stella.smartsosrelay.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [UserEntity::class, ContactEntity::class, SosEventEntity::class], version = 5, exportSchema = false)
abstract class SosDatabase : RoomDatabase() {
    abstract fun sosDao(): SosDao

    companion object {
        @Volatile
        private var INSTANCE: SosDatabase? = null

        // Migration from v1 to v2: add triggerReason column
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE sos_event_table ADD COLUMN triggerReason TEXT NOT NULL DEFAULT 'MANUAL'")
            }
        }

        // Migration from v2 to v3: add isRegistered column to user_table
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE user_table ADD COLUMN isRegistered INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // Migration from v3 to v4: Production architecture —
        //   sos_event_table: add eventIdHash, syncStatus, relayCount
        //   user_table: add deviceUuid, googleAccountId
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // SOS event table — new production fields
                database.execSQL(
                    "ALTER TABLE sos_event_table ADD COLUMN eventIdHash INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE sos_event_table ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'LOCAL'"
                )
                database.execSQL(
                    "ALTER TABLE sos_event_table ADD COLUMN relayCount INTEGER NOT NULL DEFAULT 0"
                )

                // User table — stable identity + Google link
                database.execSQL(
                    "ALTER TABLE user_table ADD COLUMN deviceUuid TEXT NOT NULL DEFAULT ''"
                )
                database.execSQL(
                    "ALTER TABLE user_table ADD COLUMN googleAccountId TEXT"
                )
            }
        }

        // Migration from v4 to v5: Firestore userId system —
        //   user_table: add firestoreUserId for canonical server-side user identification
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE user_table ADD COLUMN firestoreUserId TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        fun getDatabase(context: Context): SosDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SosDatabase::class.java,
                    "sos_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
