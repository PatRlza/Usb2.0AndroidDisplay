package com.example.usbdisplay.scrcpy

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.example.usbdisplay.adb.AdbStream
import java.nio.ByteBuffer

/**
 * Звук с телефона: scrcpy-server (audio_codec=raw) шлёт несжатый PCM 16 бит, 48 кГц, стерео.
 * Формат: 4 байта кодека (raw = 0x00726177; 0 — звук выключен, 1 — телефон не смог захватить звук),
 * затем пакеты с заголовком 12 байт (8 — метка/флаги, 4 — размер) и данными PCM.
 * Несжатый звук — это нулевая задержка на кодирование/декодирование; по USB это ≈ 1,5 Мбит/с.
 * Если звук копится быстрее, чем играется, лишнее отбрасывается (задержка не растёт).
 */
class AudioPlayer(
    private val stream: AdbStream,
    private val isStopped: () -> Boolean,
    private val log: (String) -> Unit,
    /** Режим минимальной задержки: меньший буфер и запас. */
    private val tight: Boolean = false,
    /** Размер «порции» звукового вывода ТВ в кадрах (0 — неизвестно): буфер делаем в несколько порций, а не по умолчанию. */
    private val framesPerBurst: Int = 0
) {
    companion object {
        private const val RATE = 48000
        private const val BYTES_PER_SEC = RATE * 4
        private const val CODEC_RAW = 0x00726177
    }

    private val maxBacklog = if (tight) BYTES_PER_SEC / 16 else BYTES_PER_SEC / 8 // 62 или 125 мс

    @Volatile
    var droppedMs = 0L
        private set

    fun start() {
        Thread({ run() }, "audio").also { it.isDaemon = true; it.start() }
    }

    private fun run() {
        var track: AudioTrack? = null
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val reader = StreamReader(stream, isStopped)
            val codec = reader.readFully(4) ?: return
            val id = ByteBuffer.wrap(codec).int
            if (id == 0 || id == 1) {
                log("Звук: телефон не смог включить захват звука (код $id)")
                return
            }
            if (id != CODEC_RAW) {
                log("Звук: неожиданный формат ${String(codec, Charsets.US_ASCII)}")
                return
            }
            val channels = AudioFormat.CHANNEL_OUT_STEREO
            val enc = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioTrack.getMinBufferSize(RATE, channels, enc)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(RATE).setChannelMask(channels).setEncoding(enc).build()
                )
                .setBufferSizeInBytes(if (tight) minBuf else maxOf(minBuf * 2, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            track = t
            // Буфер по умолчанию у ТВ бывает 80–200 мс. Делаем 2–3 порции и сами увеличиваем, только если звук начнёт «рваться».
            val burst = if (framesPerBurst > 0) framesPerBurst else 480
            var targetFrames = burst * (if (tight) 2 else 3)
            t.setBufferSizeInFrames(targetFrames)
            var lastUnderruns = 0
            var lastCheck = System.nanoTime()
            t.play()
            log("Звук включён (PCM 48 кГц, стерео), буфер ≈ ${t.bufferSizeInFrames * 1000 / RATE} мс")

            while (!isStopped()) {
                val h = reader.readFully(12) ?: break
                val config = (h[0].toInt() and 0x40) != 0
                val size = ByteBuffer.wrap(h, 8, 4).int
                if (size < 0 || size > 1_000_000) break
                val data = reader.readFully(size) ?: break
                if (config) continue
                if (stream.pendingBytes() > maxBacklog) {
                    droppedMs += size * 1000L / BYTES_PER_SEC
                    continue
                }
                t.write(data, 0, data.size)
                val now = System.nanoTime()
                if (now - lastCheck > 1_000_000_000L) {
                    lastCheck = now
                    val u = t.underrunCount
                    if (u > lastUnderruns && targetFrames < t.bufferCapacityInFrames) {
                        targetFrames = minOf(targetFrames + burst, t.bufferCapacityInFrames)
                        t.setBufferSizeInFrames(targetFrames)
                        log("Звук: буфер увеличен до ${t.bufferSizeInFrames * 1000 / RATE} мс (были разрывы)")
                    }
                    lastUnderruns = u
                }
            }
        } catch (e: Exception) {
            if (!isStopped()) log("Звук остановлен: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try {
                track?.stop()
                track?.release()
            } catch (e: Exception) {
                // уже освобождён
            }
        }
    }
}
