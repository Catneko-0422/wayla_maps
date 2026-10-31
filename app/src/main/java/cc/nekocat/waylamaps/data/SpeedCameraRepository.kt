package cc.nekocat.waylamaps.data

import cc.nekocat.waylamaps.alerts.SpeedCamera
import cc.nekocat.waylamaps.data.db.SpeedCameraDao
import cc.nekocat.waylamaps.data.db.SpeedCameraEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos

@Singleton
class SpeedCameraRepository @Inject constructor(
    private val dao: SpeedCameraDao,
) {
    /** Cameras inside a square box of roughly [radiusMeters] around the given point. */
    suspend fun camerasNear(lat: Double, lng: Double, radiusMeters: Double): List<SpeedCamera> {
        val dLat = radiusMeters / METERS_PER_DEGREE_LAT
        val dLng = radiusMeters / (METERS_PER_DEGREE_LAT * cos(Math.toRadians(lat)))
        return dao.inBox(lat - dLat, lat + dLat, lng - dLng, lng + dLng).map { it.toDomain() }
    }

    private companion object {
        const val METERS_PER_DEGREE_LAT = 111_320.0
    }
}

private fun SpeedCameraEntity.toDomain() = SpeedCamera(
    id = id,
    lat = lat,
    lng = lng,
    bearingDeg = bearingDeg,
    limitKmh = limitKmh,
    address = address,
    direction = directionText,
)
