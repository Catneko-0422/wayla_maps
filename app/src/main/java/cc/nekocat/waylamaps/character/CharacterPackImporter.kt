package cc.nekocat.waylamaps.character

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Unpacks a character pack zip into `<charactersDir>/<id>/`.
 *
 * The zip holds `character.json` at its root (or inside a single top folder), voice fragments
 * under `voice/<language>/`, and optionally a model. Only known file types are kept; paths
 * leaving the pack, oversized packs and ids of bundled characters are refused.
 */
class CharacterPackImporter(
    private val charactersDir: File,
    private val reservedIds: Set<String>,
    /** Every fragment id in the phrase table, to report what the pack lacks. */
    private val knownFragments: Set<String>,
    private val primaryLanguage: String = "zh-TW",
    private val maxTotalBytes: Long = 300L * 1024 * 1024,
    private val maxEntries: Int = 10_000,
) {
    sealed interface Result {
        data class Imported(val id: String, val name: String, val recorded: Int, val missing: Int) : Result
        data class Failed(val reason: Reason) : Result
    }

    enum class Reason { NOT_A_PACK, INVALID_MANIFEST, RESERVED_ID, UNSAFE_PATH, TOO_LARGE, IO_ERROR }

    private val allowedExtensions = setOf("json", "wav", "glb", "png", "txt")

    fun import(input: InputStream): Result {
        val staging = File(charactersDir, ".incoming-${UUID.randomUUID()}")
        try {
            unzip(input, staging)?.let { return Result.Failed(it) }
            val root = packRoot(staging) ?: return Result.Failed(Reason.NOT_A_PACK)
            val manifest = try {
                CharacterManifest.parse(File(root, CharacterManifest.FILE_NAME).readText())
            } catch (_: IllegalArgumentException) {
                return Result.Failed(Reason.INVALID_MANIFEST)
            }
            if (manifest.id in reservedIds) return Result.Failed(Reason.RESERVED_ID)

            val target = File(charactersDir, manifest.id)
            target.deleteRecursively()
            if (!root.renameTo(target)) throw IOException("Cannot move pack into place")
            val recorded = File(target, "voice/$primaryLanguage").listFiles { f -> f.extension == "wav" }
                .orEmpty().map { it.nameWithoutExtension }.toSet()
            return Result.Imported(
                id = manifest.id,
                name = manifest.name,
                recorded = recorded.count { it in knownFragments },
                missing = (knownFragments - recorded).size,
            )
        } catch (_: IOException) {
            return Result.Failed(Reason.IO_ERROR)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun unzip(input: InputStream, staging: File): Reason? {
        val canonicalStaging = staging.canonicalFile
        var total = 0L
        var entries = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > maxEntries) return Reason.TOO_LARGE
                val name = entry.name
                if (name.startsWith("/") || "\\" in name || name.split('/').any { it == ".." }) return Reason.UNSAFE_PATH
                val out = File(staging, name).canonicalFile
                if (!out.path.startsWith(canonicalStaging.path + File.separator)) return Reason.UNSAFE_PATH
                if (entry.isDirectory) continue
                // macOS archive litter and unknown file types are skipped.
                if (name.startsWith("__MACOSX/") || out.extension.lowercase() !in allowedExtensions) continue
                out.parentFile?.mkdirs()
                out.outputStream().use { sink ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxTotalBytes) return Reason.TOO_LARGE
                        sink.write(buffer, 0, read)
                    }
                }
            }
        }
        return null
    }

    /** The folder holding character.json: the zip root or its single top folder. */
    private fun packRoot(staging: File): File? {
        if (File(staging, CharacterManifest.FILE_NAME).isFile) return staging
        val folders = staging.listFiles { f -> f.isDirectory }.orEmpty()
        return folders.singleOrNull()?.takeIf { File(it, CharacterManifest.FILE_NAME).isFile }
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}
