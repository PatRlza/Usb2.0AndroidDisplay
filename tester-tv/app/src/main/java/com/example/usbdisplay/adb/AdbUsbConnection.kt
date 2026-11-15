package com.example.usbdisplay.adb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADB-клиент поверх USB (ТВ = хост, телефон = устройство с включённой отладкой).
 *
 * Использование:
 *   val adb = AdbUsbConnection(usbConnection, adbInterface, key)
 *   adb.connect { показать "подтвердите на телефоне" }
 *   val text = adb.shell("getprop ro.product.model")
 *   adb.close()
 */
class AdbUsbConnection(
    private val usb: UsbDeviceConnection,
    private val itf: UsbInterface,
    private val key: AdbKey,
    private val clientName: String = "usbdisplay@tv"
) : Closeable {

    private val epIn: UsbEndpoint
    private val epOut: UsbEndpoint

    private val writeLock = Any()
    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private val nextLocalId = AtomicInteger(1)

    @Volatile
    private var active = false
    private var reader: Thread? = null

    /** Строка устройства из CNXN (например "device::ro.product.name=...;features=..."). */
    var banner: String = ""
        private set

    /** Максимальный размер данных в одном сообщении, о котором договорились. */
    @Volatile
    var maxPayload: Int = 4096
        private set

    init {
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until itf.endpointCount) {
            val ep = itf.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
        }
        epIn = inEp ?: throw IOException("У ADB-интерфейса нет bulk IN")
        epOut = outEp ?: throw IOException("У ADB-интерфейса нет bulk OUT")
    }

    // ------------------------------------------------------------ подключение

    /**
     * Рукопожатие и авторизация. Если ключ ещё не известен телефону, он покажет окно
     * «Разрешить отладку по USB?» — тогда вызывается [onWaitingForUser] и мы ждём до [timeoutMs].
     */
    fun connect(timeoutMs: Long = 120_000, onWaitingForUser: () -> Unit = {}) {
        if (!usb.claimInterface(itf, true)) throw IOException("Не удалось захватить ADB-интерфейс")
        active = true

        sendMessage(
            AdbMessage(Adb.A_CNXN, Adb.VERSION, Adb.advertisedMaxData, "host::\u0000".toByteArray())
        )

        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var signatureSent = false
        var publicKeySent = false
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) throw SocketTimeoutException("Телефон не подтвердил подключение вовремя")
            val msg = readMessage(remaining)
            when (msg.command) {
                Adb.A_CNXN -> {
                    banner = String(msg.data, Charsets.UTF_8).trimEnd('\u0000')
                    maxPayload = minOf(msg.arg1, Adb.advertisedMaxData).coerceAtLeast(4096)
                    break
                }
                Adb.A_AUTH -> {
                    if (msg.arg0 != Adb.AUTH_TOKEN) continue
                    if (!signatureSent) {
                        sendMessage(AdbMessage(Adb.A_AUTH, Adb.AUTH_SIGNATURE, 0, key.sign(msg.data)))
                        signatureSent = true
                    } else if (!publicKeySent) {
                        sendMessage(
                            AdbMessage(Adb.A_AUTH, Adb.AUTH_RSAPUBLICKEY, 0, key.publicKeyPayload(clientName))
                        )
                        publicKeySent = true
                        onWaitingForUser()
                    }
                    // если ключ уже отправлен — просто ждём решения пользователя
                }
                else -> { /* лишнее до CNXN игнорируем */ }
            }
        }

        reader = Thread({ readerLoop() }, "adb-usb-reader").also {
            it.isDaemon = true
            it.start()
        }
    }

    // ------------------------------------------------------------ потоки и команды

    /** Открыть сервис на телефоне: "shell:...", "localabstract:имя", "sync:" и т.п. */
    fun open(service: String, timeoutMs: Long = 10_000): AdbStream {
        val id = nextLocalId.getAndIncrement()
        val stream = AdbStream(this, id)
        streams[id] = stream
        sendMessage(AdbMessage(Adb.A_OPEN, id, 0, (service + "\u0000").toByteArray(Charsets.UTF_8)))
        if (!stream.awaitOpen(timeoutMs)) {
            streams.remove(id)
            throw IOException("ADB: телефон не открыл сервис «$service»")
        }
        return stream
    }

    /** Выполнить команду оболочки и вернуть весь вывод. */
    fun shell(command: String, timeoutMs: Long = 20_000): String {
        val s = open("shell:$command")
        val out = ByteArrayOutputStream()
        val end = SystemClock.elapsedRealtime() + timeoutMs
        try {
            while (true) {
                val left = end - SystemClock.elapsedRealtime()
                if (left <= 0) break
                var chunk: ByteArray? = null
                var timedOut = false
                try {
                    chunk = s.read(left)
                } catch (e: SocketTimeoutException) {
                    timedOut = true
                }
                if (timedOut || chunk == null) break
                out.write(chunk)
            }
        } finally {
            s.close()
        }
        return out.toString("UTF-8")
    }

    internal fun forget(localId: Int) {
        streams.remove(localId)
    }

    // ------------------------------------------------------------ ввод-вывод

    internal fun sendMessage(msg: AdbMessage) {
        synchronized(writeLock) {
            writeFully(msg.headerBytes())
            if (msg.data.isNotEmpty()) writeFully(msg.data)
        }
    }

    private fun writeFully(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val n = minOf(data.size - off, CHUNK)
            val sent = usb.bulkTransfer(epOut, data, off, n, 5000)
            if (sent < 0) throw IOException("USB: ошибка записи")
            off += sent
        }
    }

    /** Читает ровно len байт. timeoutMs <= 0 — ждать, пока соединение активно. */
    private fun readFully(len: Int, timeoutMs: Long): ByteArray {
        val out = ByteArray(len)
        var got = 0
        val deadline = if (timeoutMs > 0) SystemClock.elapsedRealtime() + timeoutMs else Long.MAX_VALUE
        while (got < len) {
            if (!active) throw IOException("Соединение закрыто")
            val n = usb.bulkTransfer(epIn, out, got, minOf(len - got, CHUNK), 1000)
            if (n > 0) {
                got += n
            } else if (SystemClock.elapsedRealtime() > deadline) {
                throw SocketTimeoutException("USB: таймаут чтения")
            }
        }
        return out
    }

    private fun readMessage(timeoutMs: Long): AdbMessage {
        val h = ByteBuffer.wrap(readFully(Adb.HEADER_SIZE, timeoutMs)).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = h.int
        val arg0 = h.int
        val arg1 = h.int
        val len = h.int
        h.int // data_check — не проверяем
        val magic = h.int
        if (magic != (cmd xor -1)) throw IOException("ADB: неверная сигнатура сообщения")
        if (len < 0 || len > Adb.HOST_MAX_DATA) throw IOException("ADB: неверный размер данных: $len")
        val data = if (len > 0) readFully(len, 10_000) else AdbMessage.EMPTY
        return AdbMessage(cmd, arg0, arg1, data)
    }

    // статистика входящих пакетов (для диагностики задержки)
    private val wrteCount = java.util.concurrent.atomic.AtomicLong()
    private val wrteBytes = java.util.concurrent.atomic.AtomicLong()

    @Volatile
    private var wrteMax = 0

    /** Число принятых ADB-пакетов с данными, их средний и максимальный размер (байт) с прошлого вызова. */
    fun takeWrteStats(): Triple<Long, Long, Int> {
        val n = wrteCount.getAndSet(0)
        val b = wrteBytes.getAndSet(0)
        val m = wrteMax
        wrteMax = 0
        return Triple(n, if (n > 0) b / n else 0L, m)
    }

    private fun readerLoop() {
        try {
            while (active) {
                val m = readMessage(0)
                when (m.command) {
                    Adb.A_OKAY -> streams[m.arg1]?.onOkay(m.arg0)
                    Adb.A_WRTE -> {
                        val s = streams[m.arg1]
                        if (s != null) {
                            wrteCount.incrementAndGet()
                            wrteBytes.addAndGet(m.data.size.toLong())
                            if (m.data.size > wrteMax) wrteMax = m.data.size
                            s.onData(m.data)
                            sendMessage(AdbMessage(Adb.A_OKAY, s.localId, m.arg0))
                        }
                    }
                    Adb.A_CLSE -> streams.remove(m.arg1)?.onClosed()
                    else -> { /* AUTH/CNXN после подключения не ожидаем */ }
                }
            }
        } catch (e: Exception) {
            // соединение оборвалось или закрыто
        } finally {
            streams.values.forEach { it.onClosed() }
            streams.clear()
        }
    }

    override fun close() {
        val wasActive = active
        active = false
        reader?.join(1500)
        if (wasActive) {
            try {
                usb.releaseInterface(itf)
            } catch (e: Exception) {
                // не критично: соединение всё равно закрываем
            }
        }
        usb.close()
    }

    private companion object {
        /** Размер одного USB-запроса; больше 16 КБ на части Android/ядер не поддерживается. */
        const val CHUNK = 16384
    }
}
