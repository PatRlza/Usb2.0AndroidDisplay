package com.example.usbdisplay.scrcpy

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Аппаратный декодер H.264 с выводом прямо на Surface (без копирования кадров через память приложения).
 * Включён режим низкой задержки (Android 11+), как показал тестер: у декодера ТВ он поддерживается.
 */
class VideoDecoder(private val surface: Surface) {

    private var codec: MediaCodec? = null
    private var outThread: Thread? = null

    @Volatile
    private var running = false

    // статистика (читается из потока интерфейса)
    val framesIn = AtomicLong()
    val framesShown = AtomicLong()
    val framesDropped = AtomicLong()

    /** Декодированные кадры, которые не показывали, потому что уже был готов более свежий. */
    val framesSkippedDisplay = AtomicLong()

    /** Момент (System.nanoTime) последнего касания: время отклика считается до первого кадра, пришедшего после него. */
    @Volatile
    var tapNs = 0L

    /** Последний замер «касание → кадр показан декодером» и среднее по последним замерам, мс (−1 — замеров нет). */
    @Volatile
    var responseMs = -1.0

    @Volatile
    var responseAvgMs = -1.0
    private val decodeNsSum = AtomicLong()
    private val decodeCount = AtomicLong()
    private val queuedAt = ConcurrentHashMap<Long, Long>()

    @Volatile
    private var inWaitMaxNs = 0L

    /** Самое долгое ожидание свободного входного буфера декодера с прошлого вызова, мс. */
    fun takeInputWaitMaxMs(): Double {
        val v = inWaitMaxNs
        inWaitMaxNs = 0L
        return v / 1_000_000.0
    }

    /** Заметка о возможностях декодера для текущего размера (показывается в журнале). */
    @Volatile
    var note: String = ""
        private set

    fun configure(width: Int, height: Int) {
        release()
        // Попытка 0: все известные ключи низкой задержки (общий и производителей: MediaTek/MStar, Amlogic,
        // Qualcomm, Exynos). Незнакомые ключи декодер игнорирует; если же из-за них не удалось настроить —
        // попытка 1 без дополнительных ключей.
        var lastError: Exception? = null
        for (attempt in 0..1) {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            format.setInteger(MediaFormat.KEY_PRIORITY, 0) // 0 = реальное время
            if (attempt == 0) {
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, 120) // подсказка: работать на максимальной скорости
                format.setInteger("vdec-lowlatency", 1)
                format.setInteger("vendor.low-latency.enable", 1)
                format.setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
                format.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
            }
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                val n = try {
                    val caps = c.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                    val size = caps.isSizeSupported(width, height)
                    val rate = caps.areSizeAndRateSupported(width, height, 60.0)
                    "Декодер ${c.name}: размер ${width}x$height ${if (size) "поддерживается" else "НЕ ПОДДЕРЖИВАЕТСЯ"}, " +
                        "60 к/с ${if (rate) "тянет" else "НЕ тянет"}"
                } catch (e: Exception) {
                    "Декодер ${c.name}"
                }
                c.configure(format, surface, null, 0)
                c.start()
                note = n + if (attempt == 0) "; ключи низкой задержки: все" else "; ключи низкой задержки: только базовые"
                codec = c
                running = true
                outThread = Thread({ outputLoop(c) }, "decoder-out").also { it.start() }
                return
            } catch (e: Exception) {
                lastError = e
                try {
                    c.release()
                } catch (e2: Exception) {
                    // не создан до конца
                }
            }
        }
        throw lastError ?: IllegalStateException("Не удалось настроить декодер")
    }

    /** Отдать пакет декодеру. true — принят. Конфигурационный пакет (SPS/PPS) обязателен перед кадрами. */
    fun queue(data: ByteArray, ptsUs: Long, isConfig: Boolean, isKey: Boolean): Boolean {
        val c = codec ?: return false
        try {
            // Ждём свободный буфер, а не выбрасываем кадр сразу: потерянный кадр портит картинку до ключевого.
            val waitStart = System.nanoTime()
            val deadline = waitStart + if (isConfig) 1_000_000_000L else 100_000_000L
            var idx = -1
            while (idx < 0) {
                idx = c.dequeueInputBuffer(10_000L)
                if (idx < 0 && System.nanoTime() > deadline) {
                    if (!isConfig) framesDropped.incrementAndGet()
                    return false
                }
            }
            val waited = System.nanoTime() - waitStart
            if (waited > inWaitMaxNs) inWaitMaxNs = waited
            val buf = c.getInputBuffer(idx) ?: return false
            if (data.size > buf.capacity()) {
                framesDropped.incrementAndGet()
                return false
            }
            buf.clear()
            buf.put(data)
            var flags = 0
            if (isConfig) flags = flags or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            if (isKey) flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
            if (!isConfig) {
                if (queuedAt.size > 500) queuedAt.clear()
                queuedAt[ptsUs] = System.nanoTime()
                framesIn.incrementAndGet()
            }
            c.queueInputBuffer(idx, 0, data.size, ptsUs, flags)
            return true
        } catch (e: IllegalStateException) {
            return false
        }
    }

    /**
     * Декодируем ВСЕ кадры (иначе картинка «сыплется»), но показываем только самый свежий из готовых.
     * Если бы мы показывали каждый кадр, очередь разгребалась бы со скоростью экрана (60 Гц); при потоке тоже 60 к/с
     * начальная очередь не рассасывалась бы никогда и превращалась в постоянную задержку.
     * Непоказанный кадр возвращается декодеру сразу, поэтому очередь «съедается» со скоростью декодера.
     */
    private fun outputLoop(c: MediaCodec) {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (e: Exception) {
            // не удалось повысить приоритет
        }
        var info = MediaCodec.BufferInfo()
        var next = MediaCodec.BufferInfo()
        while (running) {
            try {
                var i = c.dequeueOutputBuffer(info, 20_000L)
                if (i < 0) continue
                while (true) {
                    val j = c.dequeueOutputBuffer(next, 0L)
                    if (j < 0) break
                    c.releaseOutputBuffer(i, false)
                    framesSkippedDisplay.incrementAndGet()
                    countDecoded(info)
                    i = j
                    val tmp = info
                    info = next
                    next = tmp
                }
                val arrival = countDecoded(info)
                c.releaseOutputBuffer(i, true) // true = показать сразу
                framesShown.incrementAndGet()
                val tn = tapNs
                if (tn != 0L && arrival >= tn) {
                    val ms = (System.nanoTime() - tn) / 1_000_000.0
                    responseMs = ms
                    responseAvgMs = if (responseAvgMs < 0) ms else responseAvgMs * 0.7 + ms * 0.3
                    tapNs = 0L
                }
            } catch (e: IllegalStateException) {
                break
            }
        }
    }

    /** Учитывает время декодирования кадра; возвращает момент, когда кадр пришёл на ТВ (0 — неизвестно). */
    private fun countDecoded(info: MediaCodec.BufferInfo): Long {
        val t = queuedAt.remove(info.presentationTimeUs)
        if (t != null) {
            decodeNsSum.addAndGet(System.nanoTime() - t)
            decodeCount.incrementAndGet()
            return t
        }
        return 0L
    }

    /** Кадр пропущен намеренно (ждём ключевой кадр, чтобы убрать накопившуюся задержку). */
    fun markSkipped() {
        framesDropped.incrementAndGet()
    }

    /** Среднее время «пакет отдан декодеру → кадр показан» в миллисекундах с прошлого вызова. */
    fun takeDecodeAvgMs(): Double {
        val n = decodeCount.getAndSet(0)
        val sum = decodeNsSum.getAndSet(0)
        return if (n > 0) sum / n / 1_000_000.0 else 0.0
    }

    fun release() {
        running = false
        try {
            outThread?.join(500)
        } catch (e: InterruptedException) {
            // продолжаем освобождение
        }
        outThread = null
        val c = codec
        codec = null
        if (c != null) {
            try {
                c.stop()
            } catch (e: Exception) {
                // уже остановлен
            }
            try {
                c.release()
            } catch (e: Exception) {
                // уже освобождён
            }
        }
        queuedAt.clear()
    }
}
