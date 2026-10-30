package cc.nekocat.waylamaps.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import cc.nekocat.waylamaps.data.db.SpeedCameraDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Copies the bundled camera asset into Room when the database is empty or the asset is newer. */
@Singleton
class SpeedCameraImporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: SpeedCameraDao,
) {
    suspend fun importIfNeeded() = withContext(Dispatchers.IO) {
        val asset = context.assets.open(SpeedCameraAsset.FILE_NAME)
            .bufferedReader()
            .use { SpeedCameraAsset.parse(it.readText()) }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_VERSION, null) == asset.version && dao.count() > 0) return@withContext

        dao.replaceAll(asset.cameras.map { it.toEntity() })
        prefs.edit { putString(KEY_VERSION, asset.version) }
        Log.i(TAG, "Imported ${asset.cameras.size} speed cameras (version ${asset.version})")
    }

    private companion object {
        const val TAG = "SpeedCameraImporter"
        const val PREFS = "speed_cameras"
        const val KEY_VERSION = "asset_version"
    }
}
