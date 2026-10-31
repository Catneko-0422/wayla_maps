package cc.nekocat.waylamaps.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SpeedCameraEntity::class, EnforcementPointEntity::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class WaylaDatabase : RoomDatabase() {
    abstract fun speedCameraDao(): SpeedCameraDao

    abstract fun enforcementPointDao(): EnforcementPointDao

    companion object {
        const val NAME = "wayla.db"
    }
}
