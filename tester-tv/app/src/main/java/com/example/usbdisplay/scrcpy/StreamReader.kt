package com.example.usbdisplay.scrcpy

import com.example.usbdisplay.adb.AdbStream
import java.net.SocketTimeoutException

/** Чтение точного числа байт из потока ADB (куски приходят произвольного размера). */
class StreamReader(
    private val stream: AdbStream,
    private val isStopped: () -> Boolean
) {
    private var cur = ByteArray(0)
    private var pos = 0

    /** null — поток закрыт или чтение остановлено. */
    fun readFully(n: Int): ByteArray? {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            if (pos >= cur.size) {
                cur = nextChunk() ?: return null
                pos = 0
            }
            val c = minOf(n - got, cur.size - pos)
            System.arraycopy(cur, pos, out, got, c)
            pos += c
            got += c
        }
        return out
    }

    private fun nextChunk(): ByteArray? {
        while (!isStopped()) {
            try {
                return stream.read(500)
            } catch (e: SocketTimeoutException) {
                // ждём дальше
            }
        }
        return null
    }
}
