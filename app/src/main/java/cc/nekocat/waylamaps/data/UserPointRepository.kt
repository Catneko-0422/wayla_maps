package cc.nekocat.waylamaps.data

import cc.nekocat.waylamaps.alerts.UserPoint
import cc.nekocat.waylamaps.data.db.UserPointDao
import cc.nekocat.waylamaps.data.db.UserPointEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos

@Singleton
class UserPointRepository @Inject constructor(
    private val dao: UserPointDao,
) {
    val all: Flow<List<UserPoint>> = dao.all().map { points -> points.map { it.toDomain() } }

    suspend fun pointsNear(lat: Double, lng: Double, radiusMeters: Double): List<UserPoint> {
        val dLat = radiusMeters / METERS_PER_DEGREE_LAT
        val dLng = radiusMeters / (METERS_PER_DEGREE_LAT * cos(Math.toRadians(lat)))
        return dao.inBox(lat - dLat, lat + dLat, lng - dLng, lng + dLng).map { it.toDomain() }
    }

    suspend fun add(lat: Double, lng: Double, createdAt: Long): Long =
        dao.insert(UserPointEntity(lat = lat, lng = lng, createdAt = createdAt))

    suspend fun delete(id: Long) = dao.delete(id)

    private companion object {
        const val METERS_PER_DEGREE_LAT = 111_320.0
    }
}

private fun UserPointEntity.toDomain() = UserPoint(id = id, lat = lat, lng = lng, createdAt = createdAt)
