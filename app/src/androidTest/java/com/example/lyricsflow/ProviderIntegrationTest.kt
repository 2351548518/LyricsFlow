package com.example.lyricsflow

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lyricsflow.core.*
import com.example.lyricsflow.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun neteaseRealLyricsAndComments(): Unit = runBlocking {
        val api = MusicApi(Store(context))
        val results = api.search(Provider.NETEASE, "晴天 周杰伦")
        assertTrue("NetEase search must return real candidates", results.isNotEmpty())
        val t = api.resolveNet("186016")
        assertTrue(t.title.contains("晴天"))
        assertTrue(api.lyrics(t, true).lines.isNotEmpty())
        val hot = api.comments(t, true, 0, refresh = true)
        assertTrue(hot.comments.any { it.author.isNotBlank() && it.text.isNotBlank() })
        val latest = api.comments(t, false, 0, refresh = true)
        assertTrue(latest.comments.isNotEmpty())
        android.util.Log.i(
            "LyricsFlowQA",
            "NetEase search=${results.size} lines=${api.lyrics(t).lines.size} hot=${hot.comments.size} latest=${latest.comments.size}",
        )
    }

    @Test
    fun qqRealLyricsAndComments(): Unit = runBlocking {
        val api = MusicApi(Store(context))
        val t = api.resolveQQ("0039MnYb0qxYhV")
        val candidates = api.search(Provider.QQ, "晴天 周杰伦")
        assertTrue(
            "QQ smartbox fallback should resolve a real candidate",
            candidates.any { it.sourceId == t.sourceId },
        )
        assertTrue("QQ numeric songid is required", t.sourceId.matches(Regex("\\d+")))
        assertTrue(t.mid.isNotBlank())
        val lyrics = api.lyrics(t, true)
        assertTrue(lyrics.lines.isNotEmpty())
        val hot = api.comments(t, true, 0, refresh = true)
        assertTrue(hot.comments.any { it.author.isNotBlank() && it.text.isNotBlank() })
        val latest = api.comments(t, false, 0, refresh = true)
        assertTrue(latest.comments.isNotEmpty())
        android.util.Log.i(
            "LyricsFlowQA",
            "QQ id=${t.sourceId} mid=${t.mid} lines=${lyrics.lines.size} hot=${hot.comments.size} latest=${latest.comments.size}",
        )
    }

    @Test
    fun storeAndBackupKeepCredentialsOutAndRejectZipTraversal() {
        val graph = (context.applicationContext as LyricsFlowApp).graph
        val bytes = Transfer.backup(graph)
        val parsed = Transfer.validateBackup(bytes)
        assertNotNull(parsed)
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use {
            it.putNextEntry(java.util.zip.ZipEntry("../credentials.xml"))
            it.write("bad".toByteArray())
            it.closeEntry()
        }
        assertThrows(Exception::class.java) { Transfer.validateBackup(out.toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) {
            StyleCodec.parse("{\"format\":\"other\"}")
        }
        assertEquals("classic", StyleCodec.parse(StyleCodec.preset()).mode)
    }
}
