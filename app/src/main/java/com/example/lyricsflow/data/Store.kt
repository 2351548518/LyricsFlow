package com.example.lyricsflow.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.lyricsflow.core.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

class Store(context: Context) : SQLiteOpenHelper(context, "lyricsflow.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE entries (kind TEXT NOT NULL, id TEXT NOT NULL, body TEXT NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY(kind,id))"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("需要显式数据库迁移")
    }

    @Synchronized
    fun put(kind: String, id: String, body: String) {
        writableDatabase.insertWithOnConflict(
            "entries",
            null,
            ContentValues().apply {
                put("kind", kind)
                put("id", id)
                put("body", body)
                put("updated", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        if (kind == "cache") {
            fun bytes() =
                readableDatabase
                    .rawQuery(
                        "SELECT COALESCE(SUM(length(CAST(body AS BLOB))),0) FROM entries WHERE kind='cache'",
                        null,
                    )
                    .use {
                        it.moveToFirst()
                        it.getLong(0)
                    }
            while (bytes() > 100L * 1024 * 1024) writableDatabase.execSQL(
                "DELETE FROM entries WHERE kind='cache' AND id=(SELECT id FROM entries WHERE kind='cache' ORDER BY updated ASC LIMIT 1)"
            )
        }
    }

    @Synchronized
    fun get(kind: String, id: String, maxAge: Long = Long.MAX_VALUE): String? =
        readableDatabase
            .query(
                "entries",
                arrayOf("body", "updated"),
                "kind=? AND id=?",
                arrayOf(kind, id),
                null,
                null,
                null,
            )
            .use { c ->
                if (c.moveToFirst() && System.currentTimeMillis() - c.getLong(1) <= maxAge)
                    c.getString(0)
                else null
            }

    @Synchronized
    fun all(kind: String): List<Pair<String, String>> =
        readableDatabase
            .query(
                "entries",
                arrayOf("id", "body"),
                "kind=?",
                arrayOf(kind),
                null,
                null,
                "updated DESC",
            )
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }

    @Synchronized
    fun clearCache() {
        writableDatabase.delete("entries", "kind=?", arrayOf("cache"))
    }

    @Synchronized
    fun remove(kind: String, id: String) {
        writableDatabase.delete("entries", "kind=? AND id=?", arrayOf(kind, id))
    }

    @Synchronized
    fun restore(rows: List<Triple<String, String, String>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { (k, id, b) -> put(k, id, b) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

class Secrets(context: Context) {
    private val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getKey("lyricsflow-key", null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                "lyricsflow-key",
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                }
                .generateKey()
    }

    fun write(value: String) {
        if (value.isEmpty()) {
            prefs.edit().clear().apply()
            return
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        prefs
            .edit()
            .putString(
                "key",
                Base64.encodeToString(c.iv + c.doFinal(value.toByteArray()), Base64.NO_WRAP),
            )
            .apply()
    }

    fun read(): String = runCatching {
        val b = Base64.decode(prefs.getString("key", null) ?: return "", Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
        String(c.doFinal(b.copyOfRange(12, b.size)))
    }
        .getOrDefault("")
}

fun trackJson(t: Track) =
    JSONObject()
        .put("title", t.title)
        .put("artist", t.artist)
        .put("artists", JSONArray().put(t.artist))
        .put("album", t.album)
        .put("duration", t.duration)
        .put("durationMs", t.duration)
        .put("provider", t.provider.name)
        .put("sourceId", t.sourceId)
        .put("mid", t.mid)

fun readTrack(o: JSONObject) =
    Track(
        o.getString("title"),
        o.optString("artist"),
        o.optString("album"),
        o.optLong("duration"),
        Provider.valueOf(o.optString("provider", "LOCAL")),
        o.optString("sourceId"),
        o.optString("mid"),
    )

fun lyricsJson(l: Lyrics): JSONObject =
    JSONObject()
        .put("source", l.source.name)
        .put("instrumental", l.instrumental)
        .put(
            "lines",
            JSONArray().apply {
                l.lines.forEach { row ->
                    put(
                        JSONObject()
                            .put("startMs", row.start ?: JSONObject.NULL)
                            .put("endMs", row.end ?: JSONObject.NULL)
                            .put("text", row.text)
                            .put(
                                "translation",
                                JSONObject()
                                    .put("language", "zh-Hans")
                                    .put("text", row.translation)
                                    .put("kind", row.translationKind)
                                    .put("model", row.translationModel),
                            )
                            .put("romanization", row.romanization)
                            .put(
                                "words",
                                JSONArray().apply {
                                    row.words.forEach { w ->
                                        put(
                                            JSONObject()
                                                .put("text", w.text)
                                                .put("startMs", w.start)
                                                .put("endMs", w.end)
                                        )
                                    }
                                },
                            )
                    )
                }
            },
        )

fun readLyrics(o: JSONObject): Lyrics {
    val a = o.getJSONArray("lines")
    require(a.length() <= 10000) { "行数过多" }
    return Lyrics(
        (0 until a.length())
            .map { n ->
                val r = a.getJSONObject(n)
                val ws = r.optJSONArray("words") ?: JSONArray()
                val t = r.opt("translation")
                Line(
                    if (r.isNull("startMs")) null else r.getLong("startMs"),
                    if (r.isNull("endMs")) null else r.getLong("endMs"),
                    r.getString("text"),
                    if (t is JSONObject) t.optString("text") else r.optString("translation"),
                    r.optString("romanization").takeUnless { it == "null" }.orEmpty(),
                    (0 until ws.length()).map { i ->
                        val w = ws.getJSONObject(i)
                        Word(w.getString("text"), w.getLong("startMs"), w.getLong("endMs"))
                    },
                    if (t is JSONObject) t.optString("kind", "provider") else "provider",
                    if (t is JSONObject) t.optString("model") else "",
                )
            }
            .onEach { r ->
                require(r.start == null || r.start >= 0)
                require(r.end == null || r.start == null || r.end >= r.start)
                require(r.text.length <= 20000)
                r.words.forEach { require(it.start >= 0 && it.end >= it.start) }
            }
            .sortedBy { it.start ?: Long.MAX_VALUE },
        runCatching { Provider.valueOf(o.optString("source", "LOCAL")) }
            .getOrDefault(Provider.LOCAL),
        o.optBoolean("instrumental"),
    )
}
