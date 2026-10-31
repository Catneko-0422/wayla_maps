package cc.nekocat.waylamaps.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface EnforcementPointDao {
    @Query(
        "SELECT * FROM enforcement_points " +
            "WHERE lat BETWEEN :minLat AND :maxLat AND lng BETWEEN :minLng AND :maxLng AND kind IN (:kinds)",
    )
    suspend fun inBox(
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
        kinds: List<String>,
    ): List<EnforcementPointEntity>

    @Query("SELECT COUNT(*) FROM enforcement_points WHERE source = :source")
    suspend fun count(source: String): Int

    @Query("DELETE FROM enforcement_points WHERE source = :source")
    suspend fun deleteSource(source: String)

    @Insert
    suspend fun insertAll(points: List<EnforcementPointEntity>)

    /** Replaces one county's points, leaving the others alone. */
    @Transaction
    suspend fun replaceSource(source: String, points: List<EnforcementPointEntity>) {
        deleteSource(source)
        insertAll(points)
    }
}
