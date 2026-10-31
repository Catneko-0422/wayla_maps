package cc.nekocat.waylamaps.data.enforcement

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import cc.nekocat.waylamaps.alerts.EnforcementKind
import cc.nekocat.waylamaps.alerts.Geo
import cc.nekocat.waylamaps.data.db.EnforcementPointDao
import cc.nekocat.waylamaps.data.db.EnforcementPointEntity
import cc.nekocat.waylamaps.data.db.SpeedCameraDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the enforcement point table current, county by county, from the bundled snapshot or
 * the counties' own open data.
 *
 * Each county is handled on its own: a county whose download fails, changes format or shrinks
 * by more than half keeps its previous points, and the others still update.
 */
@Singleton
class EnforcementImporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: EnforcementPointDao,
    private val speedCameraDao: SpeedCameraDao,
) {
    enum class UpdateResult { UPDATED, UNCHANGED, REJECTED, FAILED }

    suspend fun count(): Int = dao.countAll()

    private val mutex = Mutex()
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val sources by lazy {
        EnforcementSources.parseSources(readAsset("sources.json"))
    }

    /** Imports bundled files newer than what each county has. Run after the speed cameras. */
    suspend fun importBundledIfNeeded() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val manifest = EnforcementSources.parseManifest(readAsset("manifest.json"))
            for (source in sources) {
                val bundled = manifest.files[source.key] ?: continue
                val installed = prefs.getString(versionKey(source.key), null)
                if (installed != null && installed >= manifest.version && dao.count(source.key) > 0) continue

                val bytes = context.assets.open("${EnforcementSources.DIR}/${source.key}.csv").use { it.readBytes() }
                val count = store(source.key, EnforcementCsvParser.parse(bytes))
                saveVersion(source.key, manifest.version, bundled.sha256)
                Log.i(TAG, "Imported $count bundled enforcement points for ${source.key} (${manifest.version})")
            }
        }
    }

    /** Downloads every county's current CSV. Never throws for a single county. */
    suspend fun updateFromNetwork(): Map<String, UpdateResult> = mutex.withLock {
        withContext(Dispatchers.IO) {
            sources.associate { source -> source.key to update(source) }
        }
    }

    private suspend fun update(source: EnforcementSources.Source): UpdateResult {
        val bytes = try {
            val dataset = EnforcementSources.parseDataset(String(download(EnforcementSources.datasetApiUrl(source.datasetId))))
            val url = EnforcementSources.pickCsvUrl(dataset.distribution) ?: throw IOException("No CSV resource")
            download(url)
        } catch (e: Exception) {
            // IOException, TLS failures and unexpected metadata alike: keep the county's data.
            Log.w(TAG, "${source.key}: download failed", e)
            return UpdateResult.FAILED
        }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        if (sha256 == prefs.getString(shaKey(source.key), null)) return UpdateResult.UNCHANGED

        val parsed = try {
            EnforcementCsvParser.parse(bytes)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "${source.key}: format changed", e)
            return UpdateResult.REJECTED
        }
        val minimum = maxOf(1, dao.count(source.key) / 2)
        if (parsed.points.size < minimum) {
            Log.w(TAG, "${source.key}: rejected ${parsed.points.size} points, expected at least $minimum")
            return UpdateResult.REJECTED
        }
        val count = store(source.key, parsed)
        saveVersion(source.key, LocalDate.now().toString(), sha256)
        Log.i(TAG, "${source.key}: updated to $count points (${parsed.rejectedRows} rows rejected)")
        return UpdateResult.UPDATED
    }

    /** Stores a county's points, minus speed-only points that duplicate a speed camera. */
    private suspend fun store(source: String, parsed: EnforcementCsvParser.Parsed): Int {
        val points = parsed.points.filterNot { it.kind == EnforcementKind.INTERSECTION && isCoveredBySpeedCamera(it) }
        dao.replaceSource(source, points.map { it.toEntity(source) })
        return points.size
    }

    private suspend fun isCoveredBySpeedCamera(point: EnforcementCsvParser.Point): Boolean {
        if (!EnforcementCsvParser.isSpeedOnly(point.items)) return false
        val d = SAME_PLACE_M / METERS_PER_DEGREE_LAT
        return speedCameraDao.inBox(point.lat - d, point.lat + d, point.lng - d * 1.2, point.lng + d * 1.2)
            .any { Geo.distanceMeters(point.lat, point.lng, it.lat, it.lng) <= SAME_PLACE_M }
    }

    private fun download(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code from $url")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun readAsset(name: String) =
        context.assets.open("${EnforcementSources.DIR}/$name").bufferedReader().use { it.readText() }

    private fun saveVersion(source: String, version: String, sha256: String) = prefs.edit {
        putString(versionKey(source), version)
        putString(shaKey(source), sha256)
    }

    private fun versionKey(source: String) = "version_$source"
    private fun shaKey(source: String) = "sha256_$source"
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "EnforcementImporter"
        const val PREFS = "enforcement_points"
        const val TIMEOUT_MS = 30_000
        const val SAME_PLACE_M = 30.0
        const val METERS_PER_DEGREE_LAT = 111_320.0
    }
}

private fun EnforcementCsvParser.Point.toEntity(source: String) = EnforcementPointEntity(
    source = source,
    lat = lat,
    lng = lng,
    location = location,
    items = items,
    bearingDeg = bearingDeg,
    limitKmh = limitKmh,
    kind = kind.name,
)
