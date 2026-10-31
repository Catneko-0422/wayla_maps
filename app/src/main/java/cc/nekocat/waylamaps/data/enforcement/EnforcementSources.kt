package cc.nekocat.waylamaps.data.enforcement

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `assets/enforcement/sources.json` (the county datasets) and `manifest.json` (the bundled
 * snapshot, written by `tools/fetch_enforcement.py`).
 */
object EnforcementSources {
    const val DIR = "enforcement"

    @Serializable
    data class Source(val key: String, val agency: String, val datasetId: Int)

    @Serializable
    data class SourceList(val sources: List<Source>)

    @Serializable
    data class Manifest(val version: String, val files: Map<String, BundledFile>)

    @Serializable
    data class BundledFile(val url: String, val sha256: String)

    /** The parts of a data.gov.tw `api/v2/rest/dataset/{id}` response that are used. */
    @Serializable
    data class DatasetResponse(val result: Dataset)

    @Serializable
    data class Dataset(val distribution: List<Distribution>)

    @Serializable
    data class Distribution(
        val resourceFormat: String = "",
        val resourceDescription: String = "",
        val resourceDownloadUrl: String = "",
        @SerialName("resourceField") val fields: List<Field> = emptyList(),
    )

    @Serializable
    data class Field(val name: String = "")

    private val json = Json { ignoreUnknownKeys = true }
    private val ANNOUNCEMENT = Regex("第(\\d+)次")
    private val COORDINATE = Regex("緯|Latitude", RegexOption.IGNORE_CASE)

    fun parseSources(text: String): List<Source> = json.decodeFromString<SourceList>(text).sources

    fun parseManifest(text: String): Manifest = json.decodeFromString(text)

    fun parseDataset(text: String): Dataset = json.decodeFromString<DatasetResponse>(text).result

    /**
     * The CSV resource to use: among CSVs with coordinate columns (if any have them), the latest
     * numbered announcement (第N次公告), else the first. Mirrors `pick_csv_url` in the tool.
     */
    fun pickCsvUrl(distributions: List<Distribution>): String? {
        val csvs = distributions.filter { it.resourceFormat.equals("CSV", ignoreCase = true) }
        val candidates = csvs.filter { d -> d.fields.any { COORDINATE.containsMatchIn(it.name) } }.ifEmpty { csvs }
        if (candidates.isEmpty()) return null
        fun announcement(d: Distribution) = ANNOUNCEMENT.find(d.resourceDescription)?.groupValues?.get(1)?.toInt() ?: -1
        val best = candidates.maxBy(::announcement)
        return (if (announcement(best) >= 0) best else candidates.first()).resourceDownloadUrl
    }

    fun datasetApiUrl(datasetId: Int) = "https://data.gov.tw/api/v2/rest/dataset/$datasetId"
}
