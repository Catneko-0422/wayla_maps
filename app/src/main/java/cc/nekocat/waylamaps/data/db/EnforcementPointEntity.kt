package cc.nekocat.waylamaps.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "enforcement_points", indices = [Index("lat", "lng"), Index("source")])
data class EnforcementPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Key of the dataset in `assets/enforcement/sources.json`. */
    val source: String,
    val lat: Double,
    val lng: Double,
    val location: String,
    val items: String,
    /** Enforced travel direction in degrees (0 = north); null means both ways or unknown. */
    val bearingDeg: Int?,
    val limitKmh: Int?,
    /** An [cc.nekocat.waylamaps.alerts.EnforcementKind] name. */
    val kind: String,
)
