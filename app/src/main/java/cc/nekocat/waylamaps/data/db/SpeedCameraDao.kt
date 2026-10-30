package cc.nekocat.waylamaps.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface SpeedCameraDao {
    @Query(
        "SELECT * FROM speed_cameras " +
            "WHERE lat BETWEEN :minLat AND :maxLat AND lng BETWEEN :minLng AND :maxLng",
    )
    suspend fun inBox(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double): List<SpeedCameraEntity>

    @Query("SELECT COUNT(*) FROM speed_cameras")
    suspend fun count(): Int

    @Query("DELETE FROM speed_cameras")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(cameras: List<SpeedCameraEntity>)

    @Transaction
    suspend fun replaceAll(cameras: List<SpeedCameraEntity>) {
        deleteAll()
        insertAll(cameras)
    }
}
