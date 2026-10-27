package com.example.lyricsflow.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.File

object BackgroundFiles {
    fun importImage(context: Context, bytes: ByteArray) {
        require(bytes.size <= 10_000_000) { "图片超过 10 MB" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(
            bounds.outWidth > 0 &&
                bounds.outHeight > 0 &&
                bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000
        ) {
            "图片格式或尺寸不支持"
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("图片无法读取")
        val file = AtomicFile(File(context.filesDir, "background.jpg"))
        val output = file.startWrite()
        try {
            require(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)) { "图片保存失败" }
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        } finally {
            bitmap.recycle()
        }
    }
}
