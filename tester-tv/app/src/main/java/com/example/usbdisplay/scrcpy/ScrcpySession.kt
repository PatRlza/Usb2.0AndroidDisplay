package com.example.usbdisplay.scrcpy

import android.content.Context
import android.os.SystemClock
import com.example.usbdisplay.adb.AdbStream
import com.example.usbdisplay.adb.AdbUsbConnection
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Запуск scrcpy-server на телефоне и подключение к его видеосокету.
 *
 * Подтверждено пробой на POCO F3 (scrcpy-server 5.0.1): опция start_app серверу НЕ передаётся
 * (в этой версии приложение запускает клиент через канал управления), поэтому приложение
 * на виртуальном экране мы запускаем сами командой `am start --display <id> -n <компонент>`,
 * где id берём из строки лога сервера «New display: ... (id=N)».
 *
 * Компонент (главную активность) определяем явно: неявный запуск `-a MAIN -c LAUNCHER -p <пакет>` не находит
 * приложения, у которых в фильтре нет категории DEFAULT (так у Telegram и Genshin на POCO F3).
 */
class ScrcpySession(
    private val adb: AdbUsbConnection,
    private val context: Context,
    private val log: (String) -> Unit
) {
    companion object {
        /** Должна в точности совпадать с версией файла scrcpy-server. */
        const val SERVER_VERSION = "5.0.1"
        const val REMOTE_PATH = "/data/local/tmp/scrcpy-server.jar"
        const val ASSET_NAME = "scrcpy-server"

        // Регулярное выражение с [.] не совпадает с собственной командной строкой pgrep/pkill
        private const val SERVER_PATTERN = "scrcpy[.]Server"
    }

    class Config(
        val newDisplay: Boolean,
        val width: Int = 1920,
        val height: Int = 1080,
        val bitrate: Int = 10_000_000,
        val maxFps: Int = 60,
        val app: String? = null,
        /** Ограничение большей стороны видео (0 — без ограничения). Для зеркала 1080x2400 нужно 1920. */
        val maxSize: Int = 0,
        /** Остановить приложение перед запуском на виртуальном экране (иначе уже запущенное может остаться на основном). */
        val restartApp: Boolean = true,
        /** Интервал ключевых кадров, секунды: чем меньше, тем быстрее исчезают артефакты после потери кадра. */
        val keyFrameSeconds: Int = 2,
        /** Открыть канал управления (касания, клавиши). */
        val control: Boolean = true,
        /** Передавать звук (несжатый PCM). Пробе звук не нужен: сервер ждёт подключения звукового сокета. */
        val audio: Boolean = false,
        /** Параметры кодера для минимальной задержки (при неудаче запуск повторяется без них). */
        val lowLatency: Boolean = true,
        /** Постоянный битрейт (CBR): кадры предсказуемого размера, меньше всплесков задержки. */
        val cbr: Boolean = false
    )

    private var shell: AdbStream? = null
    private var video: AdbStream? = null
    private var audio: AdbStream? = null
    private var control: AdbStream? = null
    private val logRunning = AtomicBoolean(true)
    private val displayId = AtomicInteger(-1)

    @Volatile
    private var lastServerLine = ""

    /** Звуковой поток (null, если звук выключен). */
    fun audioStream(): AdbStream? = audio

    /** Канал управления (null, если выключен в настройках сессии). */
    fun controlStream(): AdbStream? = control

    /** Загружает и запускает сервер, возвращает видеопоток (читать нужно сразу). */
    fun start(cfg: Config): AdbStream {
        val server = try {
            context.assets.open(ASSET_NAME).use { it.readBytes() }
        } catch (e: IOException) {
            throw IOException("Нет файла scrcpy-server в assets приложения")
        }

        // Серверы прошлых запусков могли не завершиться: они держат аппаратный кодер и тормозят новые трансляции.
        val old = killServers()
        log(if (old > 0) "Остановлено старых серверов: $old" else "Старых серверов на телефоне нет")

        log(if (ServerFile.ensure(adb, server)) "Сервер загружен на телефон" else "Сервер уже на телефоне")

        return try {
            launchServer(cfg, cfg.lowLatency)
        } catch (e: IOException) {
            if (!cfg.lowLatency) throw e
            log("Сервер не запустился с настройками минимальной задержки (${e.message}), пробую обычные")
            closeAll()
            killServers()
            displayId.set(-1)
            launchServer(cfg, false)
        }
    }

    private fun closeAll() {
        for (s in listOf(video, audio, control, shell)) {
            try {
                s?.close()
            } catch (e: Exception) {
                // уже закрыт
            }
        }
        video = null
        audio = null
        control = null
        shell = null
    }

    private fun launchServer(cfg: Config, lowLatency: Boolean): AdbStream {
        val scid = "%08x".format(Random.nextInt(1, Int.MAX_VALUE))
        val opts = mutableListOf(
            "log_level=info",
            "scid=$scid",
            "tunnel_forward=true",
            "audio=${cfg.audio}",
            "control=${cfg.control}",
            "video_bit_rate=${cfg.bitrate}",
            "max_fps=${cfg.maxFps}"
        )
        if (cfg.audio) opts += "audio_codec=raw"
        if (cfg.maxSize > 0) opts += "max_size=${cfg.maxSize}"

        // Параметры аппаратного кодера телефона. Для минимальной задержки: без B-кадров, высокий приоритет,
        // режим низкой задержки (общий ключ latency и расширение Qualcomm). Незнакомые ключи кодер игнорирует.
        val codec = mutableListOf<String>()
        if (cfg.keyFrameSeconds > 0) codec += "i-frame-interval=${cfg.keyFrameSeconds}"
        if (lowLatency) {
            if (cfg.cbr) codec += "bitrate-mode=2"
            codec += listOf("latency=0", "priority=0", "max-bframes=0", "vendor.qti-ext-enc-low-latency.enable=1")
        }
        if (codec.isNotEmpty()) opts += "video_codec_options=${codec.joinToString(",")}"
        if (cfg.newDisplay) opts += "new_display=${cfg.width}x${cfg.height}"

        val cmd = "CLASSPATH=$REMOTE_PATH app_process / com.genymobile.scrcpy.Server " +
            "$SERVER_VERSION ${opts.joinToString(" ")}"
        log(if (lowLatency) "Запускаю сервер (режим минимальной задержки)..." else "Запускаю сервер...")
        val sh = adb.open("shell:$cmd")
        shell = sh
        startLogThread(sh)

        val v = connectVideo(sh, scid)
            ?: throw IOException("Сервер не запустился. Последнее сообщение: $lastServerLine")
        video = v

        // Порядок подключений сервера: видео, звук (если включён), управление
        if (cfg.audio) {
            audio = try {
                adb.open("localabstract:scrcpy_$scid", 5000)
            } catch (e: IOException) {
                throw IOException("Не удалось открыть звуковой канал: ${e.message}")
            }
        }
        if (cfg.control) {
            control = try {
                adb.open("localabstract:scrcpy_$scid", 5000)
            } catch (e: IOException) {
                throw IOException("Не удалось открыть канал управления: ${e.message}")
            }
        }

        // Приложение запускаем в фоновом потоке, чтобы видео читалось сразу (иначе оно копится и даёт задержку).
        val app = cfg.app
        if (cfg.newDisplay && app != null) {
            val restart = cfg.restartApp
            val t = Thread({
                try {
                    launchApp(app, restart)
                } catch (e: Exception) {
                    log("Ошибка запуска приложения: ${e.message}")
                }
            }, "launch-app")
            t.isDaemon = true
            t.start()
        }
        return v
    }

    private fun startLogThread(sh: AdbStream) {
        val idRegex = Regex("""\(id=(\d+)\)""")
        val t = Thread {
            try {
                while (logRunning.get() && !sh.isClosed) {
                    var chunk: ByteArray? = null
                    var timedOut = false
                    try {
                        chunk = sh.read(1000)
                    } catch (e: SocketTimeoutException) {
                        timedOut = true
                    }
                    if (timedOut) continue
                    if (chunk == null) break
                    val text = String(chunk, Charsets.UTF_8).trim()
                    if (text.isEmpty()) continue
                    lastServerLine = text.lines().last()
                    if (text.contains("display", ignoreCase = true)) {
                        idRegex.find(text)?.let { displayId.set(it.groupValues[1].toInt()) }
                    }
                    log("[сервер] $text")
                }
            } catch (e: Exception) {
                // поток закрыт
            }
        }
        t.isDaemon = true
        t.start()
    }

    /** Сервер стартует не мгновенно: пробуем подключиться, пока он не начнёт слушать сокет. */
    private fun connectVideo(sh: AdbStream, scid: String): AdbStream? {
        for (attempt in 1..40) {
            if (sh.isClosed) return null
            try {
                return adb.open("localabstract:scrcpy_$scid", 3000)
            } catch (e: IOException) {
                Thread.sleep(150)
            }
        }
        return null
    }

    // ------------------------------------------------------------ запуск приложения на виртуальном экране

    private fun launchApp(pkg: String, restart: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (displayId.get() < 0 && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        val id = displayId.get()
        if (id < 0) {
            log("Не удалось определить номер виртуального экрана, приложение не запущено")
            return
        }
        if (restart) {
            log("Останавливаю $pkg (чтобы он открылся на виртуальном экране)")
            adb.shell("am force-stop $pkg", 5000)
        }

        // Способ 1: явный компонент (главная активность), найденный у телефона
        val comp = resolveComponent(pkg)
        if (comp != null) {
            log("Запускаю $comp на экране $id")
            val out = adb.shell("am start --display $id -n $comp", 10_000).trim()
            if (!startFailed(out)) {
                val warn = out.lines().firstOrNull { it.contains("Warning", ignoreCase = true) }
                if (warn != null) log("[am] $warn")
                diagnoseLaunch(id, pkg)
                return
            }
            log("[am] " + (out.lines().lastOrNull { it.isNotBlank() } ?: "нет ответа"))
        } else {
            log("У $pkg не найдена главная активность")
        }

        // Способ 2: неявный запуск по имени пакета (работает не для всех приложений)
        log("Пробую запуск по имени пакета")
        val out2 = adb.shell(
            "am start --display $id -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg",
            10_000
        ).trim()
        if (!startFailed(out2)) return
        log("[am] " + (out2.lines().lastOrNull { it.isNotBlank() } ?: "нет ответа"))

        // Диагностика: установлен ли пакет и для какого пользователя
        val info = adb.shell("pm list packages -U $pkg", 5000).trim().ifEmpty { "пакет не найден" }
        log("[диагностика] $info")
    }

    /** Через пару секунд показывает, что реально открыто на виртуальном экране (помогает понять чёрный экран). */
    private fun diagnoseLaunch(id: Int, pkg: String) {
        Thread.sleep(2500)
        val out = adb.shell("dumpsys activity activities", 8000)
        val lines = out.lines().map { it.trim() }.filter {
            it.contains("ResumedActivity") || it.startsWith("Display #") || it.contains("mDisplayId=$id") ||
                (it.contains(pkg) && it.contains("ActivityRecord"))
        }.take(8)
        log("[диагностика] экран $id: " + if (lines.isEmpty()) "ничего не найдено" else lines.joinToString(" | ") { it.take(110) })
    }

    /** Находит «имя пакета/имя активности» главной активности приложения. */
    private fun resolveComponent(pkg: String): String? {
        val base = "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER $pkg"
        for (cmd in listOf("cmd package resolve-activity --brief $base", "cmd package query-activities --brief $base")) {
            val out = adb.shell(cmd, 8000)
            val line = out.lines().map { it.trim() }.firstOrNull { it.contains('/') && !it.contains(' ') }
            if (line != null) return line
        }
        return null
    }

    private fun startFailed(out: String): Boolean =
        out.contains("Error", ignoreCase = true) || out.contains("Exception", ignoreCase = true)

    // ------------------------------------------------------------ завершение

    /** Останавливает все серверы scrcpy на телефоне, возвращает сколько их было. */
    private fun killServers(): Int {
        val digits = Regex("\\d+")
        val n = adb.shell("pgrep -f '$SERVER_PATTERN'", 4000).lines().count { it.trim().matches(digits) }
        if (n > 0) adb.shell("pkill -f '$SERVER_PATTERN'", 4000)
        return n
    }

    fun stop() {
        logRunning.set(false)
        try {
            video?.close()
        } catch (e: Exception) {
            // уже закрыт
        }
        try {
            audio?.close()
        } catch (e: Exception) {
            // уже закрыт
        }
        try {
            control?.close()
        } catch (e: Exception) {
            // уже закрыт
        }
        try {
            shell?.close()
        } catch (e: Exception) {
            // уже закрыт
        }
        try {
            killServers()
        } catch (e: Exception) {
            // соединение уже потеряно
        }
    }
}
