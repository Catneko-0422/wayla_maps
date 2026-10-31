package cc.nekocat.waylamaps.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import cc.nekocat.waylamaps.data.db.SpeedCameraDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the speed camera table current, from the bundled asset or the government open data CSV.
 *
 * Data versions are ISO dates, so the newer of the bundled asset and the last download wins.
 * The SHA-256 of the source CSV is remembered so unchanged downloads are skipped.
 */
@Singleton
class SpeedCameraImporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: SpeedCameraDao,
) {
    enum class UpdateResult { UPDATED, UNCHANGED, REJECTED }

    private val mutex = Mutex()
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    suspend fun importBundledIfNeeded() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val asset = context.assets.open(SpeedCameraAsset.FILE_NAME)
                .bufferedReader()
                .use { SpeedCameraAsset.parse(it.readText()) }
            val installed = prefs.getString(KEY_VERSION, null)
            if (installed != null && installed >= asset.version && dao.count() > 0) return@withContext

            dao.replaceAll(asset.cameras.map { it.toEntity() })
            saveVersion(asset.version, asset.sourceSha256)
            Log.i(TAG, "Imported ${asset.cameras.size} bundled speed cameras (version ${asset.version})")
        }
    }

    /** Downloads the latest CSV and replaces the table if it changed. Throws on network errors. */
    suspend fun updateFromNetwork(): UpdateResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val bytes = download()
            val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
            if (sha256 == prefs.getString(KEY_SOURCE_SHA256, null)) return@withContext UpdateResult.UNCHANGED

            val parsed = SpeedCameraCsvParser.parse(bytes.toString(Charsets.UTF_8))
            // A truncated or malformed download must never wipe out working data.
            val minimum = maxOf(MIN_CAMERAS, dao.count() / 2)
            if (parsed.cameras.size < minimum) {
                Log.w(TAG, "Rejected download: ${parsed.cameras.size} cameras, expected at least $minimum")
                return@withContext UpdateResult.REJECTED
            }

            dao.replaceAll(parsed.cameras.map { it.toEntity() })
            saveVersion(LocalDate.now().toString(), sha256)
            Log.i(TAG, "Updated to ${parsed.cameras.size} speed cameras (${parsed.rejectedRows} rows rejected)")
            UpdateResult.UPDATED
        }
    }

    private fun download(): ByteArray {
        val connection = URL(SOURCE_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw java.io.IOException("HTTP $code")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun saveVersion(version: String, sourceSha256: String?) = prefs.edit {
        putString(KEY_VERSION, version)
        putString(KEY_SOURCE_SHA256, sourceSha256)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "SpeedCameraImporter"
        const val PREFS = "speed_cameras"
        const val KEY_VERSION = "data_version"
        const val KEY_SOURCE_SHA256 = "source_sha256"
        const val MIN_CAMERAS = 1000
        const val TIMEOUT_MS = 30_000

        /** data.gov.tw dataset 7320, kept in sync with tools/convert_speed_cameras.py. */
        const val SOURCE_URL = "https://opdadm.moi.gov.tw/api/v1/no-auth/resource/api/dataset/" +
            "EA5E6FCD-B82D-43B7-A5CF-E9893253187E/resource/6165E37C-8F52-4808-8D9B-E0D484416240/download"
    }
}
