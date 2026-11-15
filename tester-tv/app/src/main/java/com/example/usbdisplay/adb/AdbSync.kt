package com.example.usbdisplay.adb

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val SYNC_DATA_MAX = 64 * 1024

private fun syncHeader(id: String, value: Int): ByteArray {
    val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
    b.put(id.toByteArray(Charsets.US_ASCII))
    b.putInt(value)
    return b.array()
}

/**
 * Загрузка файла на телефон по протоколу sync (то же, что делает `adb push`).
 * mode — права файла в десятичной записи (33188 = 0100644).
 */
fun AdbUsbConnection.push(
    remotePath: String,
    data: ByteArray,
    mode: Int = 33188,
    mtimeSec: Int = (System.currentTimeMillis() / 1000).toInt()
) {
    val s = open("sync:")
    try {
        val spec = "$remotePath,$mode".toByteArray(Charsets.UTF_8)
        s.write(syncHeader("SEND", spec.size) + spec)

        var off = 0
        while (off < data.size) {
            val n = minOf(SYNC_DATA_MAX, data.size - off)
            s.write(syncHeader("DATA", n) + data.copyOfRange(off, off + n))
            off += n
        }
        s.write(syncHeader("DONE", mtimeSec))

        // Ответ: 4 байта "OKAY" или "FAIL" + 4 байта длины (для FAIL дальше идёт текст ошибки)
        val acc = ByteArrayOutputStream()
        fun fill(n: Int) {
            while (acc.size() < n) {
                val chunk = s.read(15_000) ?: throw IOException("ADB: поток sync закрыт до ответа")
                acc.write(chunk)
            }
        }
        fill(8)
        val head = acc.toByteArray()
        val id = String(head, 0, 4, Charsets.US_ASCII)
        if (id != "OKAY") {
            val len = ByteBuffer.wrap(head, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            var msg = ""
            if (len in 1..4096) {
                fill(8 + len)
                msg = String(acc.toByteArray(), 8, len, Charsets.UTF_8)
            }
            throw IOException("push не удался: $id $msg")
        }
    } finally {
        s.close()
    }
}
