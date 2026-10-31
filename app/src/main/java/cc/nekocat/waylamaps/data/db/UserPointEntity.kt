package cc.nekocat.waylamaps.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A reminder point the rider added at their own position. Kept on the device only. */
@Entity(tableName = "user_points", indices = [Index("lat", "lng")])
data class UserPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lng: Double,
    /** Epoch milliseconds. */
    val createdAt: Long,
)
