package com.example.lyricsflow

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.os.Trace
import androidx.test.platform.app.InstrumentationRegistry
import com.example.lyricsflow.core.*
import com.example.lyricsflow.data.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Explicit opt-in benchmark. Never touches Store, real accounts, permissions or the network. */
class BackupCodecBenchmark {
    private var sink: Any? = null
    private var consumed = 0
    private fun used() = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
    private fun stat(name: String) = Debug.getRuntimeStat("art.gc.$name")?.toLongOrNull() ?: -1L
    private fun progress(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("benchmark", message) })
    }

    private fun fixture(target: Int): Pair<Map<String,ByteArray>,Int> {
        val random = Random(20261104)
        fun han(n: Int) = buildString { repeat(n) { append((0x4e00 + random.nextInt(2000)).toChar()) } }
        fun latin(n: Int) = buildString { repeat(n) { append(('a'.code + random.nextInt(26)).toChar()) } }
        val entries = JSONArray()
        var bytes = 0
        var count = 0
        while (bytes < target) {
            val lines = (0 until 32).map { i ->
                val text = han(18)
                Line(i*3000L,(i+1)*3000L,text,han(14),latin(36),
                    listOf(Word(text.substring(0,9),i*3000L,i*3000L+1400), Word(text.substring(9),i*3000L+1400,(i+1)*3000L)),
                    "machine","synthetic",listOf(LyricAlignment(text.substring(0,9),latin(18),han(7)),
                        LyricAlignment(text.substring(9),latin(18),han(7))))
            }
            val record = JSONObject().put("kind","lyrics").put("id","synthetic-$count:LOCAL")
                .put("body",lyricsJson(Lyrics(lines,Provider.LOCAL)).toString())
            bytes += record.toString().toByteArray().size + 1
            entries.put(record);count++
        }
        val files = linkedMapOf("manifest.json" to """{"format":"lyricsflow.backup","schemaVersion":"1.0"}""".toByteArray(),
            "data.json" to entries.toString().toByteArray(), "settings.json" to """{"fontSize":30,"translationSize":18,"romanizationSize":13}""".toByteArray())
        assertTrue(count <= 3000)
        assertTrue(files.values.sumOf { it.size.toLong() } <= 20_000_000)
        return files to count
    }

    private fun measure(name: String, run: Int, block: () -> Any): JSONObject {
        sink = null
        Runtime.getRuntime().gc(); System.runFinalization(); Thread.sleep(120)
        val base = used()
        val allocated = stat("bytes-allocated")
        val gcCount = stat("gc-count")
        val active = AtomicBoolean(true)
        val peak = AtomicLong(base)
        val sampler = thread(name="backup-heap-sampler",isDaemon=true) {
            while (active.get()) { peak.updateAndGet { maxOf(it,used()) }; Thread.sleep(5) }
        }
        Trace.beginSection("backup-$name")
        val cpu = Debug.threadCpuTimeNanos()
        val start = SystemClock.elapsedRealtimeNanos()
        var wall = 0L
        var cpuNanos = 0L
        try { sink = block() } finally {
            wall = SystemClock.elapsedRealtimeNanos() - start
            cpuNanos = Debug.threadCpuTimeNanos() - cpu
            peak.updateAndGet { maxOf(it,used()) }
            active.set(false);sampler.join()
            Trace.endSection()
        }
        val allocatedEnd = stat("bytes-allocated")
        val gcEnd = stat("gc-count")
        return JSONObject().put("phase",name).put("run",run).put("wallMs",wall/1e6).put("threadCpuMs",cpuNanos/1e6)
            .put("baselineHeapBytes",base).put("sampledPeakHeapBytes",peak.get()).put("sampledHeapRiseBytes",peak.get()-base)
            .put("allocatedBytes",if (allocated>=0 && allocatedEnd>=0) allocatedEnd-allocated else -1)
            .put("gcCount",if (gcCount>=0 && gcEnd>=0) gcEnd-gcCount else -1)
    }

    @Test fun measureSyntheticSnapshotCodecs() {
        org.junit.Assume.assumeTrue("Explicit performance run only",
            InstrumentationRegistry.getArguments().getString("backupCodecBenchmark") == "true")
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        require(target.packageName.endsWith(".qa")) { "Benchmark must run in an isolated .qa package" }
        val output = File(target.getExternalFilesDir(null),"backup-codec-benchmark.json")
        val all = JSONArray()
        val report = JSONObject().put("device",android.os.Build.MODEL).put("api",android.os.Build.VERSION.SDK_INT)
            .put("maxHeapBytes",Runtime.getRuntime().maxMemory()).put("build","debug-instrumented")
            .put("implementation","direct-files-single-validation")
            .put("heapSampleIntervalMs",5).put("repeats",5).put("fixtures",all)
        for (size in listOf(500_000,5_000_000,15_000_000)) {
            progress("generating $size byte synthetic fixture")
            val (files,count) = fixture(size)
            val zipped = SnapshotFiles.zip(files)
            assertEquals(count,Transfer.validateBackup(zipped).first.size)
            val phases = linkedMapOf<String,()->Any>(
                "zip" to { SnapshotFiles.zip(files) },
                "validate_zip" to { Transfer.validateBackup(zipped) },
                "validated_unzip" to { SnapshotFiles.unzip(zipped) },
                "zip_then_validate" to { Transfer.validateBackup(SnapshotFiles.zip(files)) },
                "preview_local_codec_chain" to {
                    val validated = SnapshotFiles.validate(files)
                    consumed = validated.first.size
                    files
                },
                // Same old operation counts, interleaved on the same build/device to control run variance.
                // Uses the current validator; historical baseline remains separately preserved.
                "legacy_preview_control" to {
                    val zippedAgain = SnapshotFiles.zip(files)
                    Transfer.validateBackup(zippedAgain)
                    val ready = SnapshotFiles.readArchive(zippedAgain)
                    val validated = Transfer.validateBackup(SnapshotFiles.zip(ready))
                    consumed = validated.first.size
                    ready
                })
            val samples = JSONArray()
            val item = JSONObject().put("targetBytes",size).put("uncompressedBytes",files.values.sumOf { it.size })
                .put("zipBytes",zipped.size).put("records",count).put("lyricLines",count*32).put("samples",samples)
            all.put(item)
            phases.forEach { (name,fn) -> progress("warmup $size $name");sink=fn();sink=null }
            repeat(5) { round ->
                // Rotate the phase order to reduce consistent warmup/thermal ordering effects.
                val names = phases.keys.toList()
                names.indices.forEach { n ->
                    val name = names[(n+round)%names.size]
                    progress("measure $size $name ${round+1}/5")
                    samples.put(measure(name,round+1,phases.getValue(name)))
                    output.writeText(report.toString(2))
                }
            }
            sink=null
        }
        output.writeText(report.toString(2))
        progress("complete: ${output.name}")
    }
}
