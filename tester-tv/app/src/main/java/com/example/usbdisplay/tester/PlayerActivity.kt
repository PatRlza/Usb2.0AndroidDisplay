package com.example.usbdisplay.tester

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.example.usbdisplay.adb.AdbConnector
import com.example.usbdisplay.adb.AdbStream
import com.example.usbdisplay.adb.AdbUsbConnection
import com.example.usbdisplay.adb.UsbLock
import com.example.usbdisplay.apps.AutoTune
import com.example.usbdisplay.apps.PerformanceMode
import com.example.usbdisplay.apps.PhoneInstaller
import com.example.usbdisplay.pad.PadLink
import com.example.usbdisplay.scrcpy.AudioPlayer
import com.example.usbdisplay.scrcpy.ControlChannel
import com.example.usbdisplay.scrcpy.ScrcpySession
import com.example.usbdisplay.scrcpy.StreamReader
import com.example.usbdisplay.scrcpy.VideoDecoder
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Экран трансляции: только SurfaceView + маленькая статистика поверх (кнопка OK/Menu на пульте её скрывает).
 * Видео идёт: телефон (scrcpy-server) → USB (ADB) → этот экран → MediaCodec → Surface.
 *
 * Формат потока (подтверждён пробой на scrcpy-server 5.0.1):
 *   1 пробный байт + 64 байта имя устройства + 4 байта кодек ("h264"), затем пакеты по 12 байт заголовка:
 *   - пакет сессии: старший бит 1, затем ширина и высота (по 4 байта);
 *   - медиапакет: 8 байт (бит 62 = конфигурация SPS/PPS, бит 61 = ключевой кадр, остальное — PTS в мкс) + 4 байта размер.
 *   Все числа — big-endian.
 */
class PlayerActivity : Activity(), SurfaceHolder.Callback {

    companion object {
        const val EXTRA_NEW_DISPLAY = "new_display"
        const val EXTRA_APP = "app"
        const val EXTRA_SCREEN_OFF = "screen_off"
        const val EXTRA_PAD = "pad"
        const val EXTRA_PERF = "perf"
        const val EXTRA_AUTOTUNE = "autotune"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_FPS = "fps"
        const val EXTRA_LATENCY = "latency"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_PKT = "pkt_kb"
        const val EXTRA_W = "w"
        const val EXTRA_H = "h"
        private const val ACTION_PERMISSION = "com.example.usbdisplay.tester.PLAYER_USB_PERMISSION"
    }

    private lateinit var usb: UsbManager
    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: TextView
    private val ui = Handler(Looper.getMainLooper())

    private val stop = AtomicBoolean(false)
    private var worker: Thread? = null

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var decoder: VideoDecoder? = null

    private val logLines = ArrayDeque<String>()

    // ---- управление: курсор в координатах видео, состояние «палец нажат» ----
    private lateinit var cursorView: CursorView

    @Volatile
    private var control: ControlChannel? = null

    @Volatile
    private var pad: PadLink? = null
    private var perfOn = false

    @Volatile
    private var adbConn: AdbUsbConnection? = null

    // режим «Минимум»: меньшая очередь, CBR у кодера, меньший звуковой буфер
    private val minLatency: Boolean by lazy { intent.getIntExtra(EXTRA_LATENCY, 0) == 1 }
    private val backlogLimit: Int get() = if (minLatency) 100_000 else 200_000

    // сбор статистики для адаптивного битрейта (см. AutoTune)
    private val congestion = java.util.concurrent.atomic.AtomicInteger()
    private val tuneFps = ArrayList<Float>()
    private var tuneDelaySum = 0.0
    private var tuneDelayN = 0
    private var sampleT = 0L
    private var sampleIn = 0L
    private var sampleBytes = 0L
    private var sessionStartT = 0L
    private var tuned = false
    private var perfPkg = ""
    private var videoW = 0
    private var videoH = 0
    private var cursorX = 0f
    private var cursorY = 0f
    private var pressedDown = false
    private var remoteDrag = false      // перетаскивание, запущенное долгим нажатием OK на пульте
    private var okLongHandled = false
    private var okReleaseIgnored = false
    private var stickX = 0f
    private var stickY = 0f
    private var stickScroll = 0f
    private var lastInputTick = 0L
    private var scrollAccum = 0f
    private var dpadX = 0
    private var dpadY = 0
    private var dpadHold = 0f

    @Volatile
    private var videoSize = ""

    private val bytesIn = java.util.concurrent.atomic.AtomicLong()
    private var showOverlay = true

    // Относительная задержка канала: (время прихода пакета на ТВ − метка времени кодера на телефоне) минус минимум
    // за последние ~20 с. Часы устройств не синхронизированы, поэтому абсолютное значение неизвестно,
    // зато видно, сколько задержки накопилось сверх лучшего случая (очереди, заторы USB).
    private var delayWindowStartUs = 0L
    private var delayMinPrev = Long.MAX_VALUE
    private var delayMinCur = Long.MAX_VALUE

    @Volatile
    private var delayExcessMs = 0.0

    // для расчёта скоростей между обновлениями статистики
    private var lastTick = 0L
    private var lastBytes = 0L
    private var lastIn = 0L
    private var lastShown = 0L

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_PERMISSION) startWorker()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            sampleTuning()
            updateOverlay()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        // Android 11+: просим ТВ включить игровой режим / авто-режим низкой задержки (если он есть)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setPreferMinimalPostProcessing(true)
            val supported = try {
                display?.isMinimalPostProcessingSupported
            } catch (e: Exception) {
                null
            }
            addLog("Игровой режим ТВ (минимальная обработка картинки): " + when (supported) {
                true -> "поддерживается"
                false -> "не поддерживается этим экраном"
                null -> "неизвестно"
            })
        }

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        cursorView = CursorView(this)
        cursorView.visibility = View.GONE
        root.addView(cursorView, FrameLayout.LayoutParams(-1, -1))

        overlay = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#99000000"))
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setPadding(16, 8, 16, 8)
        }
        root.addView(overlay, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        setContentView(root)

        val filter = IntentFilter(ACTION_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(permissionReceiver, filter)
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun onStart() {
        super.onStart()
        ui.post(ticker)
        lastInputTick = SystemClock.uptimeMillis()
        ui.post(inputTicker)
    }

    override fun onStop() {
        finishTuning()
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(inputTicker)
        dpadX = 0
        dpadY = 0
        stop.set(true)
        finish()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        super.onDestroy()
    }

    // ------------------------------------------------------------ управление (пульт и геймпад)

    private fun onVideoSizeUi(w: Int, h: Int) {
        val first = videoW == 0
        videoW = w
        videoH = h
        if (first) {
            cursorX = w / 2f
            cursorY = h / 2f
        }
        cursorX = cursorX.coerceIn(0f, (w - 1).toFloat())
        cursorY = cursorY.coerceIn(0f, (h - 1).toFloat())
        cursorView.setVideoSize(w, h)
        cursorView.setCursor(cursorX, cursorY, pressedDown)
        cursorView.visibility = if (control != null) View.VISIBLE else View.GONE
    }

    private fun moveCursor(dx: Float, dy: Float) {
        if (videoW == 0) return
        cursorX = (cursorX + dx).coerceIn(0f, (videoW - 1).toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, (videoH - 1).toFloat())
        cursorView.setCursor(cursorX, cursorY, pressedDown)
        if (pressedDown) control?.touch(ControlChannel.ACTION_MOVE, cursorX.toInt(), cursorY.toInt(), 1f)
    }

    private fun pressDown() {
        if (pressedDown || videoW == 0) return
        pressedDown = true
        decoder?.tapNs = System.nanoTime() // начало замера отклика
        control?.touch(ControlChannel.ACTION_DOWN, cursorX.toInt(), cursorY.toInt(), 1f)
        cursorView.setCursor(cursorX, cursorY, true)
    }

    private fun pressUp() {
        if (!pressedDown) return
        pressedDown = false
        control?.touch(ControlChannel.ACTION_UP, cursorX.toInt(), cursorY.toInt(), 0f)
        cursorView.setCursor(cursorX, cursorY, false)
    }

    private fun tap() {
        pressDown()
        pressUp()
    }

    private fun isOkKey(k: Int) = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // Плавное движение: пока стрелка зажата, курсор разгоняется (см. inputTicker);
                // короткое нажатие даёт небольшой начальный сдвиг.
                if (event.repeatCount == 0) {
                    val k = maxOf(videoW, 1280) / 1920f
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> { dpadY = -1; moveCursor(0f, -14f * k) }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { dpadY = 1; moveCursor(0f, 14f * k) }
                        KeyEvent.KEYCODE_DPAD_LEFT -> { dpadX = -1; moveCursor(-14f * k, 0f) }
                        else -> { dpadX = 1; moveCursor(14f * k, 0f) }
                    }
                    dpadHold = 0f
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.repeatCount == 0) {
                    if (pressedDown && remoteDrag) {
                        // второй клик во время перетаскивания: отпускаем
                        pressUp()
                        remoteDrag = false
                        okReleaseIgnored = true
                    } else {
                        okLongHandled = false
                        okReleaseIgnored = false
                    }
                    event.startTracking()
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (event.repeatCount == 0) pressDown() // удержание кнопки = палец прижат
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                event.startTracking()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_MENU -> return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (isOkKey(keyCode)) {
            // долгое нажатие OK на пульте: «взять» объект и таскать стрелками, следующий OK отпустит
            if (!pressedDown) {
                pressDown()
                remoteDrag = true
                okLongHandled = true
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when {
            keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                dpadY = 0
                return true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                dpadX = 0
                return true
            }
            isOkKey(keyCode) -> {
                if (okReleaseIgnored) {
                    okReleaseIgnored = false
                } else if (okLongHandled) {
                    okLongHandled = false // перетаскивание продолжается до следующего OK
                } else {
                    tap()
                }
                return true
            }
            keyCode == KeyEvent.KEYCODE_BUTTON_A -> {
                pressUp()
                return true
            }
            keyCode == KeyEvent.KEYCODE_BACK -> {
                if (event.isTracking && !event.isCanceled) {
                    // короткое нажатие «Назад» на пульте: показать/скрыть панель статистики
                    showOverlay = !showOverlay
                    updateOverlay()
                }
                return true
            }
            keyCode == KeyEvent.KEYCODE_BUTTON_B -> {
                control?.keyPress(ControlChannel.KEYCODE_BACK)
                return true
            }
            keyCode == KeyEvent.KEYCODE_BUTTON_Y -> {
                control?.keyPress(ControlChannel.KEYCODE_HOME)
                return true
            }
            keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BUTTON_START -> {
                showOverlay = !showOverlay
                updateOverlay()
                return true
            }
            keyCode == KeyEvent.KEYCODE_BUTTON_SELECT -> {
                finish()
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
            event.action == MotionEvent.ACTION_MOVE
        ) {
            var x = deadZone(event.getAxisValue(MotionEvent.AXIS_X))
            var y = deadZone(event.getAxisValue(MotionEvent.AXIS_Y))
            // у части геймпадов стрелки приходят как «шляпа», а не как клавиши
            if (x == 0f) x = event.getAxisValue(MotionEvent.AXIS_HAT_X) * 0.5f
            if (y == 0f) y = event.getAxisValue(MotionEvent.AXIS_HAT_Y) * 0.5f
            stickX = x
            stickY = y
            // правый стик по вертикали: чаще AXIS_RZ, у некоторых AXIS_RY
            val rz = deadZone(event.getAxisValue(MotionEvent.AXIS_RZ))
            val ry = deadZone(event.getAxisValue(MotionEvent.AXIS_RY))
            stickScroll = if (Math.abs(rz) > Math.abs(ry)) rz else ry
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun deadZone(v: Float): Float = if (Math.abs(v) < 0.18f) 0f else v

    /** Раз в ~16 мс: двигает курсор и прокручивает по стикам геймпада. */
    private val inputTicker = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val dt = minOf(now - lastInputTick, 50L) / 1000f
            lastInputTick = now
            if (videoW > 0) {
                if (dpadX != 0 || dpadY != 0) {
                    // скорость растёт от 180 до ≈1700 пикселей/с за 1,2 секунды удержания
                    dpadHold += dt
                    val ramp = minOf(dpadHold / 1.2f, 1f)
                    val speed = (180f + ramp * ramp * 1500f) * videoW / 1920f
                    moveCursor(dpadX * speed * dt, dpadY * speed * dt)
                }
                if (stickX != 0f || stickY != 0f) {
                    // квадратичная чувствительность: у центра точно, на краю быстро
                    val speed = 1300f * videoW / 1920f
                    moveCursor(
                        stickX * Math.abs(stickX) * speed * dt,
                        stickY * Math.abs(stickY) * speed * dt
                    )
                }
                if (stickScroll != 0f) {
                    scrollAccum += dt
                    if (scrollAccum >= 0.04f) {
                        scrollAccum = 0f
                        control?.scroll(cursorX.toInt(), cursorY.toInt(), -stickScroll * 6f)
                    }
                }
            }
            ui.postDelayed(this, 16)
        }
    }

    // ------------------------------------------------------------ Surface

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        startWorker()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surface = null
    }

    // ------------------------------------------------------------ фоновая работа

    @Synchronized
    private fun startWorker() {
        if (stop.get()) return
        if (worker?.isAlive == true) return
        worker = Thread({ runSession() }, "player-session").also { it.start() }
    }

    private fun runSession() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (e: Exception) {
            // не удалось повысить приоритет
        }
        // USB-канал один: ждём, пока другие экраны закончат работу с телефоном (и предыдущая трансляция)
        if (!UsbLock.acquire(25_000)) {
            setStatus("USB занят предыдущей операцией. Закройте экран и откройте заново.")
            return
        }
        try {
            runSessionLocked()
        } finally {
            UsbLock.release()
        }
    }

    private fun audioBurstFrames(): Int = try {
        (getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager)
            .getProperty(android.media.AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
    } catch (e: Exception) {
        0
    }

    private fun runSessionLocked() {
        var adb: AdbUsbConnection? = null
        var session: ScrcpySession? = null
        try {
            addLog("Запуск...")
            val device = AdbConnector.findDevice(usb)
            if (device == null) {
                setStatus("Телефон с включённой отладкой по USB не найден. Подключите его и откройте экран заново.")
                return
            }
            if (!usb.hasPermission(device)) {
                setStatus("Подтвердите доступ к USB-устройству на экране ТВ...")
                requestPermission(device)
                return
            }

            setStatus("Подключаюсь к телефону...")
            com.example.usbdisplay.adb.Adb.advertisedMaxData = intent.getIntExtra(EXTRA_PKT, 256) * 1024
            adb = AdbConnector.connect(applicationContext, usb, device) {
                setStatus("Подтвердите отладку по USB на экране телефона (до 2 минут)")
            }

            adbConn = adb
            val newDisplay = intent.getBooleanExtra(EXTRA_NEW_DISPLAY, false)
            val app = intent.getStringExtra(EXTRA_APP)
            if (intent.getBooleanExtra(EXTRA_PERF, false)) {
                perfPkg = app ?: ""
                PerformanceMode.apply(adb, app) { addLog(it) }
                perfOn = true
            }
            addLog(
                "Битрейт %.1f Мбит/с%s, %d к/с, %dx%d".format(
                    intent.getIntExtra(EXTRA_BITRATE, 10_000_000) / 1e6,
                    if (intent.getBooleanExtra(EXTRA_AUTOTUNE, false)) " (авто)" else "",
                    intent.getIntExtra(EXTRA_FPS, 60), intent.getIntExtra(EXTRA_W, 1920), intent.getIntExtra(EXTRA_H, 1080)
                )
            )
            session = ScrcpySession(adb, applicationContext) { addLog(it) }
            val video = session.start(
                ScrcpySession.Config(
                    newDisplay = newDisplay,
                    app = app,
                    width = intent.getIntExtra(EXTRA_W, 1920),
                    height = intent.getIntExtra(EXTRA_H, 1080),
                    bitrate = intent.getIntExtra(EXTRA_BITRATE, 10_000_000),
                    maxFps = intent.getIntExtra(EXTRA_FPS, 60),
                    cbr = minLatency,
                    // зеркало 1080x2400 больше, чем тянет декодер ТВ: ограничиваем до 864x1920
                    maxSize = if (newDisplay) 0 else 1920,
                    control = true,
                    audio = intent.getBooleanExtra(EXTRA_AUDIO, true)
                )
            )

            val cs = session.controlStream()
            if (cs != null) {
                val ch = ControlChannel(cs) { addLog(it) }
                ch.start()
                control = ch
            }

            // Физический экран телефона: гасим только если выбрано (сенсорный тачпад при этом может не работать)
            if (control != null && intent.getBooleanExtra(EXTRA_SCREEN_OFF, false)) {
                control?.displayPower(false)
            }

            // Тачпад на телефоне: приложение само ставится на телефон по кабелю и запускается
            if (intent.getBooleanExtra(EXTRA_PAD, false)) startPad(adb)

            session.audioStream()?.let { AudioPlayer(it, { stop.get() }, { m -> addLog(m) }, minLatency, audioBurstFrames()).start() }

            setStatus("Жду видео...")
            readVideo(video)
        } catch (e: Exception) {
            if (!stop.get()) setStatus("ОШИБКА: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            decoder?.release()
            decoder = null
            pad?.close()
            pad = null
            adbConn = null
            if (perfOn) {
                try {
                    adb?.let { PerformanceMode.revert(it, perfPkg.ifEmpty { null }) }
                } catch (e: Exception) {
                    // соединение уже потеряно
                }
            }
            // сначала канал управления: он вернёт экран телефона во включённое состояние
            try {
                control?.close()
            } catch (e: Exception) {
                // уже закрыт
            }
            control = null
            try {
                session?.stop()
            } catch (e: Exception) {
                // уже остановлена
            }
            try {
                adb?.close()
            } catch (e: Exception) {
                // уже закрыто
            }
        }
    }

    /** Ставит приложение на телефон (если нужно), запускает режим тачпада и подключается к нему. */
    private fun startPad(adb: AdbUsbConnection) {
        PhoneInstaller.ensure(applicationContext, adb) { addLog(it) }
        if (connectPad(adb, false)) return
        addLog("Запускаю тачпад на телефоне…")
        adb.shell("input keyevent 224", 3000) // разбудить экран
        adb.shell("am start -n $packageName/.PadActivity", 6000)
        for (i in 1..10) {
            Thread.sleep(500)
            if (stop.get()) return
            if (connectPad(adb, i == 10)) return
        }
    }

    /** Подключается к тачпаду на телефоне. true — подключились. */
    private fun connectPad(adb: AdbUsbConnection, logFail: Boolean): Boolean {
        val s = try {
            adb.open("localabstract:" + com.example.usbdisplay.pad.PadProtocol.SOCKET, 2500)
        } catch (e: IOException) {
            if (logFail) addLog("Тачпад на телефоне не отвечает")
            return false
        }
        val link = PadLink(s, { stop.get() }, object : PadLink.Listener {
            private fun k() = maxOf(videoW, videoH) / 1920f
            override fun onMove(dx: Float, dy: Float) {
                ui.post { moveCursor(dx * 1.7f * k(), dy * 1.7f * k()) }
            }
            override fun onTap() { ui.post { tap() } }
            override fun onBack() { control?.keyPress(ControlChannel.KEYCODE_BACK) }
            override fun onDown() { ui.post { pressDown() } }
            override fun onUp() { ui.post { pressUp() } }
            override fun onScroll(dy: Float) {
                ui.post { control?.scroll(cursorX.toInt(), cursorY.toInt(), (dy / 10f).coerceIn(-16f, 16f)) }
            }
            override fun onClosed() {
                if (!stop.get()) addLog("Тачпад на телефоне отключился")
            }
        })
        link.start()
        pad = link
        addLog("Тачпад на телефоне подключён")
        return true
    }

    private fun readVideo(video: AdbStream) {
        val reader = StreamReader(video) { stop.get() }

        // 1 пробный байт + 64 байта имени устройства, затем 4 байта идентификатора кодека
        reader.readFully(1 + 64) ?: throw IOException("Сервер закрыл поток")
        val codec = reader.readFully(4) ?: throw IOException("Сервер закрыл поток")
        if (String(codec, Charsets.US_ASCII) != "h264") {
            throw IOException("Неожиданный кодек: " + String(codec, Charsets.US_ASCII))
        }
        bytesIn.addAndGet(69)

        var skipping = false
        while (!stop.get()) {
            val h = reader.readFully(12) ?: break
            bytesIn.addAndGet(12)

            if ((h[0].toInt() and 0x80) != 0) {
                // пакет сессии: размер видео (может прийти снова при повороте экрана)
                val w = int32(h, 4)
                val hh = int32(h, 8)
                onVideoSize(w, hh)
                continue
            }

            val flags = h[0].toInt() and 0xFF
            val isConfig = (flags and 0x40) != 0
            val isKey = (flags and 0x20) != 0
            val pts = int64(h, 0) and 0x1FFFFFFFFFFFFFFFL
            val size = int32(h, 8)
            if (size < 0 || size > 8 * 1024 * 1024) throw IOException("Неверный размер пакета: $size")
            val data = reader.readFully(size) ?: break
            bytesIn.addAndGet(size.toLong())
            if (!isConfig) trackDelay(pts)

            val dec = decoder ?: continue
            if (!isConfig) {
                // Если данные копятся быстрее, чем мы их обрабатываем, пропускаем кадры до ближайшего
                // ключевого: так задержка не растёт, а картинка не «сыплется».
                if (!skipping && !isKey && video.pendingBytes() > backlogLimit) {
                    skipping = true
                    congestion.incrementAndGet()
                    // не ждём очередной ключевой кадр (до 2 с): просим сервер сделать его сейчас
                    control?.resetVideo()
                }
                if (skipping) {
                    if (isKey) {
                        skipping = false
                    } else {
                        dec.markSkipped()
                        continue
                    }
                }
            }
            val ok = dec.queue(data, pts, isConfig, isKey)
            if (!ok && !isConfig) skipping = true // кадр потерян: ждём ключевой
        }
        if (!stop.get()) setStatus("Поток закончился (телефон отключён или сервер остановлен)")
    }

    private fun trackDelay(ptsUs: Long) {
        val nowUs = SystemClock.elapsedRealtimeNanos() / 1000
        val d = nowUs - ptsUs
        if (delayWindowStartUs == 0L) delayWindowStartUs = nowUs
        if (nowUs - delayWindowStartUs > 10_000_000) {
            delayMinPrev = delayMinCur
            delayMinCur = Long.MAX_VALUE
            delayWindowStartUs = nowUs
        }
        if (d < delayMinCur) delayMinCur = d
        val excessMs = (d - minOf(delayMinPrev, delayMinCur)) / 1000.0
        delayExcessMs = delayExcessMs * 0.9 + excessMs * 0.1
    }

    private fun onVideoSize(w: Int, h: Int) {
        val s = surface ?: return
        videoSize = "${w}x$h"
        decoder?.release()
        val d = VideoDecoder(s)
        d.configure(w, h)
        decoder = d
        fit(w, h)
        control?.setScreenSize(w, h)
        ui.post { onVideoSizeUi(w, h) }
        addLog("Видео ${w}x$h. ${d.note}")
    }

    /** Вписать картинку в экран с сохранением пропорций (для 16:9 на ТВ 16:9 — на весь экран). */
    private fun fit(w: Int, h: Int) {
        ui.post {
            val cw = root.width
            val ch = root.height
            if (cw == 0 || ch == 0 || w == 0 || h == 0) return@post
            val scale = minOf(cw.toFloat() / w, ch.toFloat() / h)
            surfaceView.layoutParams =
                FrameLayout.LayoutParams((w * scale).toInt(), (h * scale).toInt(), Gravity.CENTER)
        }
    }

    private fun requestPermission(d: UsbDevice) {
        val i = Intent(ACTION_PERMISSION).setPackage(packageName)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, flags))
    }

    // ------------------------------------------------------------ статистика

    private fun setStatus(text: String) = addLog(text)

    private fun addLog(text: String) {
        if (text.isBlank()) return
        synchronized(logLines) {
            for (line in text.trim().lines()) {
                logLines.addLast(line)
                while (logLines.size > 7) logLines.removeFirst()
            }
        }
    }

    /** Раз в секунду записывает кадры/с и поток для адаптивного битрейта (не зависит от того, видна ли панель). */
    private fun sampleTuning() {
        val dec = decoder ?: return
        val now = SystemClock.elapsedRealtime()
        if (sessionStartT == 0L) sessionStartT = now
        if (sampleT == 0L) {
            sampleT = now
            sampleIn = dec.framesIn.get()
            sampleBytes = bytesIn.get()
            return
        }
        if (now - sampleT < 1000) return
        val dt = (now - sampleT) / 1000.0
        val fin = dec.framesIn.get()
        val b = bytesIn.get()
        val mbps = (b - sampleBytes) * 8 / dt / 1e6
        if (now - sessionStartT > 5000 && mbps >= 1.0) {   // первые секунды (запуск игры) не считаем
            tuneFps.add(((fin - sampleIn) / dt).toFloat())
            tuneDelaySum += delayExcessMs
            tuneDelayN++
        }
        sampleT = now
        sampleIn = fin
        sampleBytes = b
    }

    /** При выходе: уточняем битрейт этого приложения по собранной статистике. */
    private fun finishTuning() {
        if (tuned) return
        tuned = true
        if (!intent.getBooleanExtra(EXTRA_AUTOTUNE, false)) return
        val pkg = intent.getStringExtra(EXTRA_APP) ?: return
        val prefs = getSharedPreferences("launcher", Context.MODE_PRIVATE)
        AutoTune.update(
            prefs, pkg, intent.getIntExtra(EXTRA_BITRATE, AutoTune.START_BITRATE),
            intent.getIntExtra(EXTRA_FPS, 60), tuneFps, congestion.get(),
            if (tuneDelayN > 0) tuneDelaySum / tuneDelayN else 0.0
        )
    }

    private fun updateOverlay() {
        if (!showOverlay) {
            overlay.visibility = View.GONE
            return
        }
        overlay.visibility = View.VISIBLE

        val now = SystemClock.elapsedRealtime()
        val dec = decoder
        val sb = StringBuilder()
        if (dec != null) {
            val bytes = bytesIn.get()
            val fin = dec.framesIn.get()
            val shown = dec.framesShown.get()
            if (lastTick > 0) {
                val dt = (now - lastTick) / 1000.0
                if (dt > 0) {
                    sb.append("Видео $videoSize\n")
                    sb.append("Поток: %.1f Мбит/с\n".format((bytes - lastBytes) * 8 / dt / 1e6))
                    sb.append("Кадров на входе: %.0f/с, показано: %.0f/с\n".format((fin - lastIn) / dt, (shown - lastShown) / dt))
                    sb.append("Задержка канала: +%.0f мс (сверх минимума)\n".format(delayExcessMs))
                    adbConn?.takeWrteStats()?.let { (n, avg, mx) ->
                        sb.append("ADB-пакетов: %d/с, средний %.1f КБ, макс %.1f КБ\n".format(n * 2, avg / 1024.0, mx / 1024.0))
                    }
                    sb.append("Ожидание декодера: до %.1f мс\n".format(dec.takeInputWaitMaxMs()))
                    if (dec.responseMs >= 0) {
                        sb.append("Отклик на касание: %.0f мс (среднее %.0f)\n".format(dec.responseMs, dec.responseAvgMs))
                    }
                    sb.append(
                        "Декодер: %.1f мс, потеряно: %d, не показано: %d".format(
                            dec.takeDecodeAvgMs(), dec.framesDropped.get(), dec.framesSkippedDisplay.get()
                        )
                    )
                }
            }
            lastBytes = bytes
            lastIn = fin
            lastShown = shown
            lastTick = now
        }
        val lines = synchronized(logLines) { logLines.toList() }
        if (lines.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("--- журнал ---")
            for (l in lines) sb.append("\n").append(l.take(110))
        }
        sb.append("\n[Пульт: стрелки — курсор, OK — клик, держать OK — перетаскивание (OK — отпустить)]")
        sb.append("\n[Назад — скрыть/показать эту панель, держать Назад — выход]")
        overlay.text = sb.toString()
    }

    // ------------------------------------------------------------ big-endian

    private fun int32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun int64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }
}

/** Простой курсор-стрелка поверх видео. Координаты — в пикселях видео, размещение — как у вписанной картинки. */
class CursorView(context: Context) : View(context) {
    private var vw = 1920
    private var vh = 1080
    private var cx = 0f
    private var cy = 0f
    private var pressed = false
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hide = Runnable { if (!pressed) animate().alpha(0f).setDuration(300).start() }

    fun setVideoSize(w: Int, h: Int) {
        vw = maxOf(w, 1)
        vh = maxOf(h, 1)
        invalidate()
    }

    /** Двигает курсор и показывает его; через 5 секунд бездействия он гаснет. */
    fun setCursor(x: Float, y: Float, isPressed: Boolean) {
        cx = x
        cy = y
        pressed = isPressed
        animate().cancel()
        alpha = 1f
        removeCallbacks(hide)
        postDelayed(hide, 5000)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hide)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val s = minOf(width.toFloat() / vw, height.toFloat() / vh)
        val px = (width - vw * s) / 2f + cx * s
        val py = (height - vh * s) / 2f + cy * s
        val d = resources.displayMetrics.density
        val r = (if (pressed) 16f else 20f) * d
        // Круг, центр которого — точка нажатия
        fill.color = if (pressed) Color.argb(110, 255, 70, 70) else Color.argb(80, 255, 255, 255)
        canvas.drawCircle(px, py, r, fill)
        ring.color = if (pressed) Color.argb(220, 255, 70, 70) else Color.argb(200, 255, 255, 255)
        canvas.drawCircle(px, py, r, ring)
        dot.color = Color.argb(230, 0, 0, 0)
        canvas.drawCircle(px, py, 2.5f * d, dot)
    }
}
