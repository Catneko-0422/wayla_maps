package cc.nekocat.waylamaps.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SpeedCameraEntity::class, EnforcementPointEntity::class, UserPointEntity::class],
    version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class WaylaDatabase : RoomDatabase() {
    abstract fun speedCameraDao(): SpeedCameraDao

    abstract fun enforcementPointDao(): EnforcementPointDao

    abstract fun userPointDao(): UserPointDao

    companion object {
        const val NAME = "wayla.db"
    }
}
