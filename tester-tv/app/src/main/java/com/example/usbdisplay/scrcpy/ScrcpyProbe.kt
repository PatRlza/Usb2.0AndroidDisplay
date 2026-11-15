package com.example.usbdisplay.scrcpy

import android.content.Context
import android.os.SystemClock
import com.example.usbdisplay.adb.AdbStream
import com.example.usbdisplay.adb.AdbUsbConnection
import com.example.usbdisplay.adb.push
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Проба (этап 3б): загружает scrcpy-server на телефон, запускает его и читает видеопоток по USB.
 * Ничего не декодирует: показывает лог сервера, первые байты потока и скорость потока.
 * По этим данным мы сверяем формат потока с реальным, прежде чем писать приёмник (этап 3в).
 */
class ScrcpyProbe(
    private val adb: AdbUsbConnection,
    private val context: Context,
    private val log: (String) -> Unit
) {
    companion object {
        const val SERVER_VERSION = ScrcpySession.SERVER_VERSION
        const val REMOTE_PATH = ScrcpySession.REMOTE_PATH
        const val ASSET_NAME = ScrcpySession.ASSET_NAME
        const val READ_SECONDS = 6
        private const val HEAD_BYTES = 160
    }

    fun run(newDisplay: Boolean) {
        val server = try {
            context.assets.open(ASSET_NAME).use { it.readBytes() }
        } catch (e: IOException) {
            log(
                "НЕТ файла scrcpy-server в приложении. Скопируйте файл scrcpy-server из папки scrcpy " +
                    "в tester-tv/app/src/main/assets/ и пересоберите приложение."
            )
            return
        }

        log("Загружаю сервер на телефон (${server.size} байт)...")
        val t0 = SystemClock.elapsedRealtime()
        adb.push(REMOTE_PATH, server)
        log("Загружено за ${SystemClock.elapsedRealtime() - t0} мс")

        val scid = "%08x".format(Random.nextInt(1, Int.MAX_VALUE))
        val opts = mutableListOf(
            "log_level=info",
            "scid=$scid",
            "tunnel_forward=true",
            "audio=false",
            "control=false",
            "video_bit_rate=10000000",
            "max_fps=60"
        )
        // start_app серверу не передаётся: в 5.0.1 это не серверная опция (см. ScrcpySession)
        if (newDisplay) opts += "new_display=1920x1080"
        val cmd = "CLASSPATH=$REMOTE_PATH app_process / com.genymobile.scrcpy.Server " +
            "$SERVER_VERSION ${opts.joinToString(" ")}"
        log("Запуск сервера: ${opts.joinToString(" ")}")

        val shell = adb.open("shell:$cmd")
        val running = AtomicBoolean(true)
        val logThread = Thread {
            try {
                while (running.get() && !shell.isClosed) {
                    var chunk: ByteArray? = null
                    var timedOut = false
                    try {
                        chunk = shell.read(1000)
                    } catch (e: SocketTimeoutException) {
                        timedOut = true
                    }
                    if (timedOut) continue
                    if (chunk == null) break
                    val text = String(chunk, Charsets.UTF_8).trimEnd()
                    if (text.isNotEmpty()) log("[сервер] $text")
                }
            } catch (e: Exception) {
                // поток закрыт
            }
        }
        logThread.isDaemon = true
        logThread.start()

        var video: AdbStream? = null
        try {
            video = connectVideo(shell, scid)
            if (video == null) {
                log("Не удалось подключиться к видеосокету. Причина должна быть в строках [сервер] выше (например, несовпадение версии).")
                return
            }
            log("Видеосокет открыт, читаю поток $READ_SECONDS секунд...")
            readAndReport(video)
        } finally {
            running.set(false)
            try {
                video?.close()
            } catch (e: Exception) {
                // уже закрыт
            }
            try {
                shell.close()
            } catch (e: Exception) {
                // уже закрыт
            }
        }
    }

    /** Сервер стартует не мгновенно: пробуем подключиться несколько раз, пока он не начнёт слушать сокет. */
    private fun connectVideo(shell: AdbStream, scid: String): AdbStream? {
        for (attempt in 1..40) {
            if (shell.isClosed) return null
            try {
                return adb.open("localabstract:scrcpy_$scid", 3000)
            } catch (e: IOException) {
                Thread.sleep(250)
            }
        }
        return null
    }

    private fun readAndReport(video: AdbStream) {
        val head = ByteArrayOutputStream()
        var total = 0L
        var chunks = 0
        val start = SystemClock.elapsedRealtime()
        var firstAt = -1L
        while (SystemClock.elapsedRealtime() - start < READ_SECONDS * 1000L) {
            var chunk: ByteArray? = null
            var timedOut = false
            try {
                chunk = video.read(500)
            } catch (e: SocketTimeoutException) {
                timedOut = true
            }
            if (timedOut) continue
            if (chunk == null) {
                log("Поток закрыт сервером")
                break
            }
            if (firstAt < 0) firstAt = SystemClock.elapsedRealtime() - start
            total += chunk.size
            chunks++
            if (head.size() < HEAD_BYTES) head.write(chunk, 0, minOf(chunk.size, HEAD_BYTES - head.size()))
        }

        if (total == 0L) {
            log("Данных нет. Проверьте строки [сервер] выше.")
            return
        }
        val elapsed = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1)
        val mbit = total * 8.0 / elapsed / 1000.0
        log("Первые данные через $firstAt мс. Получено $total байт в $chunks кусках, ≈ %.2f Мбит/с".format(mbit))
        analyzeHead(head.toByteArray())
    }

    private fun analyzeHead(b: ByteArray) {
        log("Первые ${b.size} байт потока (hex):")
        for (i in b.indices step 16) {
            val end = minOf(i + 16, b.size)
            log("  %03d: %s".format(i, hex(b, i, end)))
        }

        // Ищем идентификатор кодека "h264" (0x68 0x32 0x36 0x34), чтобы определить, сколько байт ему предшествует.
        val marker = byteArrayOf(0x68, 0x32, 0x36, 0x34)
        var idx = -1
        for (i in 0..b.size - 4) {
            if (b[i] == marker[0] && b[i + 1] == marker[1] && b[i + 2] == marker[2] && b[i + 3] == marker[3]) {
                idx = i
                break
            }
        }
        if (idx < 0) {
            log("Идентификатор кодека «h264» в начале потока не найден. Пришлите скриншот: формат уточним.")
            return
        }
        val before = b.copyOfRange(0, idx)
        val dummy = if (before.isNotEmpty() && before[0].toInt() == 0) "есть (1 байт)" else "не обнаружен"
        val nameBytes = if (before.size > 1) before.copyOfRange(1, before.size) else before
        val name = String(nameBytes, Charsets.UTF_8).replace("\u0000", "").trim()
        log("Кодек h264 найден на смещении $idx. Пробный байт: $dummy. Имя устройства: «$name» (поле ${nameBytes.size} байт).")

        val p = idx + 4
        if (b.size >= p + 12) {
            val first = u32(b, p)
            if ((first ushr 31) == 1L) {
                log("Далее — пакет сессии: ширина=${u32(b, p + 4)}, высота=${u32(b, p + 8)} (формат новых версий).")
                log("Дальше должны идти кадры: заголовок 12 байт (метка времени+флаги 8 байт, размер 4 байта) и данные.")
            } else {
                log("Далее — ширина=${u32(b, p)}, высота=${u32(b, p + 4)} (формат старых версий).")
            }
        }
    }

    private fun u32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun hex(b: ByteArray, from: Int, to: Int): String {
        val sb = StringBuilder()
        for (i in from until to) sb.append("%02X ".format(b[i]))
        return sb.toString().trimEnd()
    }
}
