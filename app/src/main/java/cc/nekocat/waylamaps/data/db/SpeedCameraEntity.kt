package cc.nekocat.waylamaps.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "speed_cameras", indices = [Index("lat", "lng")])
data class SpeedCameraEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lng: Double,
    val city: String,
    val district: String,
    val address: String,
    /** Original 拍攝方向 text from the dataset. */
    val directionText: String,
    /** Enforced travel direction in degrees (0 = north); null means both ways or unknown. */
    val bearingDeg: Int?,
    val limitKmh: Int?,
)
