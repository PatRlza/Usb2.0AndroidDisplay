package com.example.usbdisplay.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.usbdisplay.adb.AdbUsbConnection
import com.example.usbdisplay.scrcpy.StreamReader
import java.io.File
import java.io.IOException

/**
 * Иконки приложений: приложение на телефоне (IconActivity) отдаёт их по ADB, ТВ сохраняет в файлы
 * и показывает на плитках. Формат ответа описан в IconActivity.
 */
object IconFetcher {
    private val cache = HashMap<String, Bitmap?>()

    private fun dir(context: Context) = File(context.filesDir, "icons").also { it.mkdirs() }

    /** Иконка из кэша на диске (или null). */
    fun get(context: Context, pkg: String): Bitmap? {
        synchronized(cache) {
            if (cache.containsKey(pkg)) return cache[pkg]
            val f = File(dir(context), "$pkg.png")
            val b = if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
            cache[pkg] = b
            return b
        }
    }

    /** Загружает иконки с телефона. Возвращает число полученных. */
    fun fetch(context: Context, adb: AdbUsbConnection, log: (String) -> Unit): Int {
        adb.shell("am start -n ${context.packageName}/.IconActivity", 6000)
        var stream: com.example.usbdisplay.adb.AdbStream? = null
        for (i in 1..12) {
            try {
                stream = adb.open("localabstract:dopexran_icons", 1500)
                break
            } catch (e: IOException) {
                Thread.sleep(400)
            }
        }
        if (stream == null) {
            log("Иконки: телефон не ответил (экран телефона заблокирован?)")
            return 0
        }
        var n = 0
        try {
            val r = StreamReader(stream) { false }
            val d = dir(context)
            while (true) {
                val len = r.readFully(1)?.get(0)?.toInt()?.and(0xFF) ?: break
                if (len == 0) break
                val pkg = String(r.readFully(len) ?: break, Charsets.UTF_8)
                val lb = r.readFully(4) ?: break
                val size = ((lb[0].toInt() and 0xFF) shl 24) or ((lb[1].toInt() and 0xFF) shl 16) or
                    ((lb[2].toInt() and 0xFF) shl 8) or (lb[3].toInt() and 0xFF)
                if (size < 0 || size > 2_000_000) break
                val png = r.readFully(size) ?: break
                File(d, "$pkg.png").writeBytes(png)
                n++
            }
        } finally {
            try {
                stream.close()
            } catch (e: Exception) {
                // уже закрыт
            }
        }
        synchronized(cache) { cache.clear() }
        return n
    }
}
