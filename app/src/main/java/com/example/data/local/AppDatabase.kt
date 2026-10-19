package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.data.local.converters.RoomConverters
import com.example.data.local.dao.*
import com.example.data.local.entity.*

@Database(
    entities = [
        LinkEntity::class,
        ActorEntity::class,
        StudioEntity::class,
        SettingsEntity::class
    ],
    version = 20,
    exportSchema = false
)
@TypeConverters(RoomConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun linkDao(): LinkDao
    abstract fun actorDao(): ActorDao
    abstract fun studioDao(): StudioDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_settings ADD COLUMN enableVideoPlayerGestures INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_settings ADD COLUMN navBarHeightDp INTEGER NOT NULL DEFAULT 64")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN navBarTransparency REAL NOT NULL DEFAULT 0.65")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN navBarBlurDp INTEGER NOT NULL DEFAULT 24")
            }
        }

        private val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_settings ADD COLUMN isPasscodeEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN passcodeHash TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_settings ADD COLUMN navBarActiveTabBlurDp INTEGER NOT NULL DEFAULT 16")
                db.execSQL("UPDATE app_settings SET navBarHeightDp = 70 WHERE navBarHeightDp = 64")
            }
        }

        private val MIGRATION_15_16 = object : androidx.room.migration.Migration(15, 16) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_settings ADD COLUMN navBarActiveTabIntensity REAL NOT NULL DEFAULT 0.6")
            }
        }

        private val MIGRATION_16_17 = object : androidx.room.migration.Migration(16, 17) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_settings ADD COLUMN blockMalePerformers INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_17_18 = object : androidx.room.migration.Migration(17, 18) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_settings ADD COLUMN enableGlassTophead INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN topheadGlassIntensity REAL NOT NULL DEFAULT 0.65")
            }
        }

        private val MIGRATION_18_19 = object : androidx.room.migration.Migration(18, 19) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS app_settings_temp")
                db.execSQL("""
                    CREATE TABLE app_settings_temp (
                        `id` INTEGER NOT NULL,
                        `currentTheme` TEXT NOT NULL,
                        `accentColorHex` TEXT NOT NULL,
                        `torboxApiKey` TEXT NOT NULL,
                        `realDebridApiKey` TEXT NOT NULL,
                        `stashDbApiKey` TEXT NOT NULL,
                        `geminiApiKey` TEXT NOT NULL,
                        `blurCovers` INTEGER NOT NULL,
                        `betaTestPrivacy` INTEGER NOT NULL,
                        `defaultAspectRatio` TEXT NOT NULL,
                        `showManagementCards` INTEGER NOT NULL,
                        `enableVideoPlayerGestures` INTEGER NOT NULL,
                        `transitionStyle` INTEGER NOT NULL,
                        `lastSyncTime` INTEGER NOT NULL,
                        `defaultKeysSeeded` INTEGER NOT NULL,
                        `debridOrder` TEXT NOT NULL,
                        `enableStudioFilter` INTEGER NOT NULL,
                        `blockMalePerformers` INTEGER NOT NULL,
                        `blockedStudioNames` TEXT NOT NULL,
                        `blockedStudioIds` TEXT NOT NULL,
                        `navBarHeightDp` INTEGER NOT NULL,
                        `navBarTransparency` REAL NOT NULL,
                        `navBarBlurDp` INTEGER NOT NULL,
                        `navBarActiveTabBlurDp` INTEGER NOT NULL,
                        `navBarActiveTabIntensity` REAL NOT NULL,
                        `isPasscodeEnabled` INTEGER NOT NULL,
                        `passcodeHash` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO app_settings_temp (
                        `id`, `currentTheme`, `accentColorHex`, `torboxApiKey`, `realDebridApiKey`,
                        `stashDbApiKey`, `geminiApiKey`, `blurCovers`, `betaTestPrivacy`,
                        `defaultAspectRatio`, `showManagementCards`, `enableVideoPlayerGestures`,
                        `transitionStyle`, `lastSyncTime`, `defaultKeysSeeded`, `debridOrder`,
                        `enableStudioFilter`, `blockMalePerformers`, `blockedStudioNames`,
                        `blockedStudioIds`, `navBarHeightDp`, `navBarTransparency`,
                        `navBarBlurDp`, `navBarActiveTabBlurDp`, `navBarActiveTabIntensity`,
                        `isPasscodeEnabled`, `passcodeHash`
                    )
                    SELECT
                        `id`, `currentTheme`, `accentColorHex`, `torboxApiKey`, `realDebridApiKey`,
                        `stashDbApiKey`, `geminiApiKey`, `blurCovers`, `betaTestPrivacy`,
                        `defaultAspectRatio`, `showManagementCards`, `enableVideoPlayerGestures`,
                        `transitionStyle`, `lastSyncTime`, `defaultKeysSeeded`, `debridOrder`,
                        `enableStudioFilter`, `blockMalePerformers`, `blockedStudioNames`,
                        `blockedStudioIds`, `navBarHeightDp`, `navBarTransparency`,
                        `navBarBlurDp`, `navBarActiveTabBlurDp`, `navBarActiveTabIntensity`,
                        `isPasscodeEnabled`, `passcodeHash`
                    FROM app_settings
                """.trimIndent())
                db.execSQL("DROP TABLE app_settings")
                db.execSQL("ALTER TABLE app_settings_temp RENAME TO app_settings")
            }
        }

        private val MIGRATION_19_20 = object : androidx.room.migration.Migration(19, 20) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE links ADD COLUMN urlVR TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE links ADD COLUMN magnetVR TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE links ADD COLUMN torrentUrlVR TEXT DEFAULT NULL")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "goony_database"
                )
                    .addMigrations(
                        MIGRATION_6_7,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                        MIGRATION_18_19,
                        MIGRATION_19_20
                    )
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
