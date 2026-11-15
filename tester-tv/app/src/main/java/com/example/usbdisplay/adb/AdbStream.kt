package com.example.usbdisplay.adb

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Один логический поток внутри ADB-соединения (например, "shell:..." или "localabstract:...").
 * Читать и писать можно из разных потоков.
 */
class AdbStream internal constructor(
    private val conn: AdbUsbConnection,
    val localId: Int
) {
    @Volatile
    var remoteId: Int = 0
        private set

    @Volatile
    private var opened = false

    @Volatile
    var isClosed = false
        private set

    private val openLatch = CountDownLatch(1)
    private val ackSemaphore = Semaphore(0)
    private val inbound = LinkedBlockingQueue<ByteArray>()
    private val pending = AtomicInteger()

    /** Сколько байт пришло от телефона, но ещё не прочитано приложением (для контроля задержки). */
    fun pendingBytes(): Int = pending.get()

    // ---- вызывается из потока чтения соединения ----

    internal fun onOkay(remote: Int) {
        if (!opened) {
            remoteId = remote
            opened = true
            openLatch.countDown()
        } else {
            ackSemaphore.release()
        }
    }

    internal fun onData(data: ByteArray) {
        if (data.isNotEmpty()) {
            pending.addAndGet(data.size)
            inbound.put(data)
        }
    }

    internal fun onClosed() {
        if (isClosed) return
        isClosed = true
        openLatch.countDown()
        ackSemaphore.release(1000)
        inbound.put(EOF)
    }

    internal fun awaitOpen(timeoutMs: Long): Boolean {
        openLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return opened && !isClosed
    }

    // ---- публичный API ----

    /** Следующий кусок данных. null — поток закрыт. Бросает SocketTimeoutException по таймауту. */
    fun read(timeoutMs: Long): ByteArray? {
        val chunk = inbound.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw SocketTimeoutException("ADB: данных нет")
        if (chunk === EOF) {
            inbound.put(EOF) // чтобы повторные чтения тоже видели конец
            return null
        }
        pending.addAndGet(-chunk.size)
        return chunk
    }

    /** Записать данные: режется на части и ждёт подтверждения (OKAY) после каждой. */
    fun write(data: ByteArray) {
        var off = 0
        val max = minOf(conn.maxPayload, 16 * 1024)
        while (off < data.size) {
            if (isClosed) throw IOException("ADB: поток закрыт")
            val n = minOf(max, data.size - off)
            conn.sendMessage(AdbMessage(Adb.A_WRTE, localId, remoteId, data.copyOfRange(off, off + n)))
            if (!ackSemaphore.tryAcquire(10, TimeUnit.SECONDS)) throw IOException("ADB: нет подтверждения записи")
            off += n
        }
    }

    fun close() {
        if (isClosed) return
        try {
            conn.sendMessage(AdbMessage(Adb.A_CLSE, localId, remoteId))
        } catch (e: Exception) {
            // поток уже недоступен — просто закрываем у себя
        }
        conn.forget(localId)
        onClosed()
    }

    private companion object {
        val EOF = ByteArray(0)
    }
}
