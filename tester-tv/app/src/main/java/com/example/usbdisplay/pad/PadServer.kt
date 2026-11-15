package com.example.usbdisplay.pad

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.OutputStream

/**
 * Серверная часть на телефоне: ждёт подключения ТВ и отправляет ему события тачпада и геймпада.
 * Один на весь процесс (сокет с одним именем нельзя открыть дважды).
 */
object PadServer {
    private val lock = Any()
    private var started = false

    @Volatile
    private var out: OutputStream? = null

    @Volatile
    private var current: LocalSocket? = null

    /** Вызывается из фонового потока: true — ТВ подключился, false — отключился. */
    @Volatile
    var listener: ((Boolean) -> Unit)? = null

    val connected: Boolean get() = out != null

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        Thread({ acceptLoop() }, "pad-accept").also { it.isDaemon = true; it.start() }
    }

    private fun acceptLoop() {
        val server = try {
            LocalServerSocket(PadProtocol.SOCKET)
        } catch (e: Exception) {
            synchronized(lock) { started = false }
            return
        }
        while (true) {
            val c = try {
                server.accept()
            } catch (e: Exception) {
                break
            }
            try {
                current?.close() // новое подключение вытесняет старое
            } catch (e: Exception) {
                // уже закрыто
            }
            current = c
            out = c.outputStream
            listener?.invoke(true)
            // читаем, чтобы заметить отключение ТВ
            Thread({
                try {
                    val input = c.inputStream
                    while (input.read() != -1) { /* ТВ ничего важного не шлёт */ }
                } catch (e: Exception) {
                    // закрыто
                }
                if (current === c) {
                    out = null
                    current = null
                    listener?.invoke(false)
                }
            }, "pad-read").also { it.isDaemon = true; it.start() }
        }
        synchronized(lock) { started = false }
    }

    fun send(vararg parts: ByteArray) {
        val o = out ?: return
        try {
            synchronized(lock) {
                for (p in parts) o.write(p)
                o.flush()
            }
        } catch (e: Exception) {
            out = null
            listener?.invoke(false)
        }
    }

    fun send(type: Int, vararg payload: ByteArray) {
        send(byteArrayOf(type.toByte()), *payload)
    }
}
