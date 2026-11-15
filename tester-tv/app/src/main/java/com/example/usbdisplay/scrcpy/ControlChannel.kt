package com.example.usbdisplay.scrcpy

import com.example.usbdisplay.adb.AdbStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Канал управления scrcpy-server: касания, клавиши, прокрутка, питание экрана телефона.
 *
 * Формат сообщений сверён с исходниками scrcpy (app/src/control_msg.c, enum из control_msg.h).
 * Все числа big-endian. Первый байт — тип сообщения:
 *   0  INJECT_KEYCODE        action u8, keycode u32, repeat u32, metastate u32            (14 байт)
 *   2  INJECT_TOUCH_EVENT    action u8, pointer_id u64, x u32, y u32, w u16, h u16,
 *                            pressure u16 (1.0 = 0xFFFF), action_button u32, buttons u32   (32 байта)
 *   3  INJECT_SCROLL_EVENT   x u32, y u32, w u16, h u16, hscroll i16, vscroll i16, buttons u32 (21 байт)
 *   10 SET_DISPLAY_POWER     on u8                                                        (2 байта)
 *   17 RESET_VIDEO                                                                        (1 байт)
 * w и h — размер видео (из пакета сессии): сервер сам пересчитывает координаты в размер настоящего экрана.
 *
 * Все методы неблокирующие (кладут сообщение в очередь); запись в USB идёт в отдельном потоке.
 */
class ControlChannel(
    private val stream: AdbStream,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TYPE_KEYCODE = 0
        private const val TYPE_TOUCH = 2
        private const val TYPE_SCROLL = 3
        private const val TYPE_DISPLAY_POWER = 10
        private const val TYPE_RESET_VIDEO = 17

        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2

        const val KEYCODE_HOME = 3
        const val KEYCODE_BACK = 4

        /** Обычный «палец» (не мышь): настоящий номер указателя, сервер сам сопоставит его. */
        private const val POINTER_ID = 0L
    }

    private val queue = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var running = true

    @Volatile
    private var screenW = 0

    @Volatile
    private var screenH = 0

    @Volatile
    private var screenTurnedOff = false

    @Volatile
    private var errorReported = false

    fun start() {
        Thread({ sendLoop() }, "control-send").also { it.isDaemon = true; it.start() }
        Thread({ readLoop() }, "control-read").also { it.isDaemon = true; it.start() }
    }

    /** Размер видео (из пакета сессии). Без него касания не отправляются. */
    fun setScreenSize(w: Int, h: Int) {
        screenW = w
        screenH = h
    }

    // ------------------------------------------------------------ команды

    fun touch(action: Int, x: Int, y: Int, pressure: Float) {
        val w = screenW
        val h = screenH
        if (w <= 0 || h <= 0) return
        val b = ByteBuffer.allocate(32) // порядок байт по умолчанию — big-endian
        b.put(TYPE_TOUCH.toByte())
        b.put(action.toByte())
        b.putLong(POINTER_ID)
        b.putInt(x.coerceIn(0, w - 1))
        b.putInt(y.coerceIn(0, h - 1))
        b.putShort(w.toShort())
        b.putShort(h.toShort())
        b.putShort(fixedU16(pressure).toShort())
        b.putInt(0) // action_button
        b.putInt(0) // buttons
        queue.put(b.array())
    }

    fun key(action: Int, keycode: Int) {
        val b = ByteBuffer.allocate(14)
        b.put(TYPE_KEYCODE.toByte())
        b.put(action.toByte())
        b.putInt(keycode)
        b.putInt(0) // repeat
        b.putInt(0) // metastate
        queue.put(b.array())
    }

    /** Нажать и отпустить клавишу (Назад, Домой и т.п.). */
    fun keyPress(keycode: Int) {
        key(ACTION_DOWN, keycode)
        key(ACTION_UP, keycode)
    }

    /** Колесо мыши в точке (x, y). vscroll > 0 — прокрутка вверх; диапазон −16..16. */
    fun scroll(x: Int, y: Int, vscroll: Float) {
        val w = screenW
        val h = screenH
        if (w <= 0 || h <= 0) return
        val b = ByteBuffer.allocate(21)
        b.put(TYPE_SCROLL.toByte())
        b.putInt(x.coerceIn(0, w - 1))
        b.putInt(y.coerceIn(0, h - 1))
        b.putShort(w.toShort())
        b.putShort(h.toShort())
        b.putShort(0) // hscroll
        b.putShort(fixedI16(vscroll / 16f).toShort())
        b.putInt(0) // buttons
        queue.put(b.array())
    }

    /** Включить/выключить физический экран телефона (трансляция при этом продолжается). */
    fun displayPower(on: Boolean) {
        if (!on) screenTurnedOff = true
        queue.put(byteArrayOf(TYPE_DISPLAY_POWER.toByte(), if (on) 1 else 0))
    }

    /** Попросить сервер заново начать видео (новый ключевой кадр). */
    fun resetVideo() {
        queue.put(byteArrayOf(TYPE_RESET_VIDEO.toByte()))
    }

    /** Закрыть канал; если экран телефона выключали, сначала включаем его обратно. */
    fun close() {
        if (!running) return
        running = false
        if (screenTurnedOff) {
            try {
                stream.write(byteArrayOf(TYPE_DISPLAY_POWER.toByte(), 1))
            } catch (e: Exception) {
                // канал уже недоступен
            }
        }
        try {
            stream.close()
        } catch (e: Exception) {
            // уже закрыт
        }
    }

    // ------------------------------------------------------------ потоки

    private fun sendLoop() {
        try {
            while (running) {
                val m = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                // устаревшие движения пальца не нужны, если очередь не успевает
                if (m[0].toInt() == TYPE_TOUCH && m[1].toInt() == ACTION_MOVE && queue.size > 4) continue
                stream.write(m)
            }
        } catch (e: Exception) {
            if (running && !errorReported) {
                errorReported = true
                onError("Канал управления: ${e.message}")
            }
        }
    }

    /** Сервер может присылать сообщения (буфер обмена и т.п.): читаем и отбрасываем, чтобы не копились. */
    private fun readLoop() {
        try {
            while (running && !stream.isClosed) {
                try {
                    stream.read(1000) ?: break
                } catch (e: SocketTimeoutException) {
                    // ждём дальше
                }
            }
        } catch (e: Exception) {
            // канал закрыт
        }
    }

    private fun fixedU16(f: Float): Int = (f * 65536f).toInt().coerceIn(0, 0xFFFF)

    private fun fixedI16(f: Float): Int = (f * 32768f).toInt().coerceIn(-32768, 32767)
}
