package com.example.lyricsflow

import com.example.lyricsflow.core.*
import com.example.lyricsflow.data.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private fun files() = linkedMapOf(
        "manifest.json" to """{"format":"lyricsflow.backup","schemaVersion":"1.0"}""".toByteArray(),
        "data.json" to "[]".toByteArray(),
        "settings.json" to """{"fontSize":30,"translationSize":19,"romanizationSize":14}""".toByteArray(),
    )

    private fun archive(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test fun uncompressedExportAndArchivePreserveRecordsAndSettings() {
        val f = ChoiceFixture()
        val id = "backup-codec-equivalence"
        val line = Line(0, 2000, "光", "光芒", "hikari", alignment = listOf(LyricAlignment("光", "hikari", "光芒")))
        f.graph.store.put("lyrics", id, lyricsJson(Lyrics(listOf(line), Provider.LOCAL)).toString())
        try {
            val direct = SnapshotFiles.validate(Transfer.backupFiles(f.graph, false))
            val zipped = Transfer.validateBackup(Transfer.backup(f.graph, false))
            assertEquals(direct.first, zipped.first)
            assertEquals(direct.second.toString(), zipped.second.toString())
            assertTrue(direct.first.any { it.second == id && it.third.contains("hikari") })
        } finally {
            f.graph.store.remove("lyrics", id)
            f.clean()
        }
    }

    @Test fun directValidationRejectsTheSameMalformedContentAsArchiveImport() {
        val bad = listOf(
            files().apply { this["manifest.json"] = """{"format":"other","schemaVersion":"1.0"}""".toByteArray() },
            files().apply { this["data.json"] = """[{"kind":"credentials","id":"x","body":"{}"}]""".toByteArray() },
            files().apply { this["settings.json"] = """{"overlayColor":"not-a-color"}""".toByteArray() },
            files().apply { this["settings.json"] = """{"trackPreferences":{"delay:x":10001}}""".toByteArray() },
        )
        bad.forEach {
            assertThrows(Exception::class.java) { SnapshotFiles.validate(it) }
            assertThrows(Exception::class.java) { Transfer.validateBackup(archive(it)) }
        }
        val old = files().apply { remove("settings.json") }
        assertEquals(0, Transfer.validateBackup(archive(old)).second.length())
        assertThrows(Exception::class.java) { SnapshotFiles.validate(old) }
    }

    @Test fun singleInflationStillRejectsDuplicateAndUnsafeEntries() {
        val unsafe = archive(files() + ("../data.json" to "[]".toByteArray()))
        assertThrows(Exception::class.java) { SnapshotFiles.unzip(unsafe) }
        // ZipOutputStream itself rejects duplicate names. Rename an equal-length entry in both headers.
        val duplicate = archive(files() + ("data.jsoX" to "[]".toByteArray()))
        val from = "data.jsoX".toByteArray()
        val to = "data.json".toByteArray()
        for (i in 0..duplicate.size - from.size) {
            if (from.indices.all { duplicate[i + it] == from[it] }) to.copyInto(duplicate, i)
        }
        assertThrows(Exception::class.java) { SnapshotFiles.unzip(duplicate) }
    }

    @Test fun cumulativeExpandedSizeIsBoundedBeforeParsing() {
        val oversized = linkedMapOf(
            "manifest.json" to ByteArray(10_000_000) { 32 },
            "data.json" to ByteArray(10_000_001) { 32 },
        )
        val zipped = archive(oversized)
        assertTrue(zipped.size < 100_000) // Compressed-size checks alone cannot stop this archive.
        assertThrows(IllegalArgumentException::class.java) { SnapshotFiles.readArchive(zipped) }
        assertThrows(IllegalArgumentException::class.java) { Transfer.validateBackupFiles(oversized) }
    }

    @Test fun uploadRevalidatesMutableInputEvenAfterAnEarlierSuccessfulParse() = runBlocking {
        val server = MemoryGitHub().apply { remote = files() }
        val api = GitHubApi(server)
        val repo = GitHubRepo("lyricsflow-sync", "main", 73)
        assertNull(api.snapshot("fake", "qa-user", repo).validated)
        val remote = api.snapshot("fake", "qa-user", repo, retainValidation = true)
        assertNotNull(remote.validated)
        assertSame(remote.validated, remote.validated)
        val changed = files()
        SnapshotFiles.validate(changed)
        changed["data.json"] = "invalid".toByteArray()
        try {
            api.upload("fake", "qa-user", remote, changed, remote.head)
            fail("modified upload bypassed validation")
        } catch (_: Exception) {}
        assertEquals(0, server.patchCount)
        assertTrue(server.postedPaths.isEmpty())
    }
}
