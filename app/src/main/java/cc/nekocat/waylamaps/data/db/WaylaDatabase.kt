package cc.nekocat.waylamaps.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [SpeedCameraEntity::class], version = 1)
abstract class WaylaDatabase : RoomDatabase() {
    abstract fun speedCameraDao(): SpeedCameraDao

    companion object {
        const val NAME = "wayla.db"
    }
}
