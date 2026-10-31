package cc.nekocat.waylamaps.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CharacterPackImporterTest {
    @get:Rule val temp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    private fun importer(dir: File = temp.root) =
        CharacterPackImporter(dir, reservedIds = setOf("wayla"), knownFragments = setOf("passed", "over_limit", "arrived"))

    @Test
    fun importsAPackAndCountsMissingPhrases() {
        val result = importer().import(
            zip(
                "character.json" to """{"id": "stylet", "name": "Stylet"}""",
                "voice/zh-TW/passed.wav" to "RIFF",
                "voice/zh-TW/arrived.wav" to "RIFF",
                "notes.exe" to "skip me",
            ),
        )

        assertEquals(CharacterPackImporter.Result.Imported("stylet", "Stylet", recorded = 2, missing = 1), result)
        assertTrue(File(temp.root, "stylet/voice/zh-TW/passed.wav").isFile)
        assertFalse(File(temp.root, "stylet/notes.exe").exists())
    }

    @Test
    fun acceptsASingleTopFolder() {
        val result = importer().import(
            zip("stylet/character.json" to """{"id": "stylet", "name": "Stylet"}""", "stylet/voice/zh-TW/passed.wav" to "RIFF"),
        )

        assertTrue(result is CharacterPackImporter.Result.Imported)
        assertTrue(File(temp.root, "stylet/voice/zh-TW/passed.wav").isFile)
    }

    @Test
    fun refusesPathsLeavingThePack() {
        val result = importer(File(temp.root, "characters").apply { mkdirs() }).import(
            zip("character.json" to """{"id": "x", "name": "X"}""", "../evil.json" to "{}"),
        )

        assertEquals(CharacterPackImporter.Result.Failed(CharacterPackImporter.Reason.UNSAFE_PATH), result)
        assertFalse(File(temp.root, "evil.json").exists())
    }

    @Test
    fun refusesBuiltInIdsAndBadManifests() {
        assertEquals(
            CharacterPackImporter.Result.Failed(CharacterPackImporter.Reason.RESERVED_ID),
            importer().import(zip("character.json" to """{"id": "wayla", "name": "Fake"}""")),
        )
        assertEquals(
            CharacterPackImporter.Result.Failed(CharacterPackImporter.Reason.INVALID_MANIFEST),
            importer().import(zip("character.json" to """{"id": "Bad Id!", "name": "X"}""")),
        )
        assertEquals(
            CharacterPackImporter.Result.Failed(CharacterPackImporter.Reason.NOT_A_PACK),
            importer().import(zip("readme.txt" to "hello")),
        )
    }

    @Test
    fun refusesOversizedPacks() {
        val small = CharacterPackImporter(temp.root, emptySet(), emptySet(), maxTotalBytes = 10)
        val result = small.import(zip("character.json" to """{"id": "big", "name": "Big"}"""))

        assertEquals(CharacterPackImporter.Result.Failed(CharacterPackImporter.Reason.TOO_LARGE), result)
    }

    @Test
    fun leavesNoStagingFolderBehind() {
        importer().import(zip("readme.txt" to "hello"))

        assertTrue(temp.root.listFiles().orEmpty().none { it.name.startsWith(".incoming") })
    }
}
