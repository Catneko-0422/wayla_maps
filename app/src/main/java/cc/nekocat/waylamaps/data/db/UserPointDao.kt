package cc.nekocat.waylamaps.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UserPointDao {
    @Query("SELECT * FROM user_points WHERE lat BETWEEN :minLat AND :maxLat AND lng BETWEEN :minLng AND :maxLng")
    suspend fun inBox(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double): List<UserPointEntity>

    @Query("SELECT * FROM user_points ORDER BY createdAt DESC")
    fun all(): Flow<List<UserPointEntity>>

    @Insert
    suspend fun insert(point: UserPointEntity): Long

    @Query("DELETE FROM user_points WHERE id = :id")
    suspend fun delete(id: Long)
}
