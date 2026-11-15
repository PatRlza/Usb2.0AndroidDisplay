package com.example.usbdisplay.tester

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.LocalServerSocket
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Невидимая (прозрачная) активность на телефоне: по запросу ТВ отдаёт иконки всех приложений с главным экраном.
 * ТВ запускает её командой `am start` и подключается к сокету [SOCKET]. Формат ответа:
 * для каждого приложения: длина имени пакета (1 байт), имя пакета, длина PNG (4 байта), PNG 96×96;
 * в конце — байт 0. Всё big-endian. После отправки (или через 20 с) активность закрывается.
 */
class IconActivity : Activity() {
    companion object {
        const val SOCKET = "dopexran_icons"
        private const val SIZE = 96
    }

    private var server: LocalServerSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 20_000)
        Thread({ serve() }, "icons").also { it.isDaemon = true; it.start() }
    }

    override fun onDestroy() {
        try {
            server?.close()
        } catch (e: Exception) {
            // уже закрыт
        }
        super.onDestroy()
    }

    private fun serve() {
        try {
            val s = LocalServerSocket(SOCKET)
            server = s
            val c = s.accept()
            val out = DataOutputStream(c.outputStream.buffered(64 * 1024))
            val pm = packageManager
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val seen = HashSet<String>()
            for (ri in pm.queryIntentActivities(main, 0)) {
                val pkg = ri.activityInfo.packageName
                if (!seen.add(pkg)) continue
                try {
                    val d = pm.getApplicationIcon(pkg)
                    val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
                    val cv = Canvas(bmp)
                    d.setBounds(0, 0, SIZE, SIZE)
                    d.draw(cv)
                    val png = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, png)
                    bmp.recycle()
                    val name = pkg.toByteArray(Charsets.UTF_8)
                    out.writeByte(name.size)
                    out.write(name)
                    out.writeInt(png.size())
                    out.write(png.toByteArray())
                } catch (e: Exception) {
                    // иконку получить не удалось — пропускаем приложение
                }
            }
            out.writeByte(0)
            out.flush()
            c.close()
        } catch (e: Exception) {
            // сокет закрыт или занят
        }
        runOnUiThread { finish() }
    }
}
