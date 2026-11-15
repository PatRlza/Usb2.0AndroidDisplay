package com.example.usbdisplay.pad

import com.example.usbdisplay.adb.AdbStream
import com.example.usbdisplay.scrcpy.StreamReader

/** Сторона ТВ: читает события тачпада/геймпада, пришедшие с телефона (см. [PadProtocol]). */
class PadLink(
    private val stream: AdbStream,
    private val isStopped: () -> Boolean,
    private val l: Listener
) {
    interface Listener {
        fun onMove(dx: Float, dy: Float)
        fun onTap()
        fun onBack()
        fun onDown()
        fun onUp()
        fun onScroll(dy: Float)
        fun onClosed()
    }

    fun start() {
        Thread({ run() }, "pad-link").also { it.isDaemon = true; it.start() }
    }

    fun close() {
        try {
            stream.close()
        } catch (e: Exception) {
            // уже закрыт
        }
    }

    private fun s16(b: ByteArray, o: Int): Int = (b[o].toInt() shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun run() {
        try {
            val r = StreamReader(stream, isStopped)
            while (!isStopped()) {
                val t = r.readFully(1)?.get(0)?.toInt() ?: break
                when (t) {
                    PadProtocol.MOVE -> {
                        val b = r.readFully(4) ?: break
                        l.onMove(s16(b, 0) / 8f, s16(b, 2) / 8f)
                    }
                    PadProtocol.TAP -> l.onTap()
                    PadProtocol.BACK -> l.onBack()
                    PadProtocol.DOWN -> l.onDown()
                    PadProtocol.UP -> l.onUp()
                    PadProtocol.SCROLL -> {
                        val b = r.readFully(2) ?: break
                        l.onScroll(s16(b, 0) / 8f)
                    }
                    else -> break // неизвестное сообщение: рассинхронизация, закрываем
                }
            }
        } catch (e: Exception) {
            // канал закрыт
        }
        l.onClosed()
    }
}
