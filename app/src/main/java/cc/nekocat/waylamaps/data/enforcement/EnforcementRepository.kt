package cc.nekocat.waylamaps.data.enforcement

import cc.nekocat.waylamaps.alerts.EnforcementKind
import cc.nekocat.waylamaps.alerts.EnforcementPoint
import cc.nekocat.waylamaps.data.db.EnforcementPointDao
import cc.nekocat.waylamaps.data.db.EnforcementPointEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos

@Singleton
class EnforcementRepository @Inject constructor(
    private val dao: EnforcementPointDao,
) {
    /** Points of the given kinds inside a square box of roughly [radiusMeters] around the given point. */
    suspend fun pointsNear(
        lat: Double,
        lng: Double,
        radiusMeters: Double,
        kinds: Set<EnforcementKind> = setOf(EnforcementKind.INTERSECTION),
    ): List<EnforcementPoint> {
        val dLat = radiusMeters / METERS_PER_DEGREE_LAT
        val dLng = radiusMeters / (METERS_PER_DEGREE_LAT * cos(Math.toRadians(lat)))
        return dao.inBox(lat - dLat, lat + dLat, lng - dLng, lng + dLng, kinds.map { it.name }).map { it.toDomain() }
    }

    private companion object {
        const val METERS_PER_DEGREE_LAT = 111_320.0
    }
}

private fun EnforcementPointEntity.toDomain() = EnforcementPoint(
    id = id,
    lat = lat,
    lng = lng,
    location = location,
    items = items,
    bearingDeg = bearingDeg,
    limitKmh = limitKmh,
    kind = EnforcementKind.valueOf(kind),
)
