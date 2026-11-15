package com.example.usbdisplay.tester

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import com.example.usbdisplay.adb.AdbKey
import com.example.usbdisplay.adb.AdbUsbConnection
import com.example.usbdisplay.scrcpy.ScrcpyProbe
import java.io.IOException
import android.widget.TextView

/**
 * Мини-тестер для ТВ. Отвечает на главный вопрос проекта:
 * "Может ли это ТВ выступать USB-хостом и увидит ли оно ADB-интерфейс планшета?"
 *
 * Показывает:
 *  1. заявлена ли поддержка USB host системой;
 *  2. список подключённых USB-устройств (VID/PID, интерфейсы);
 *  3. есть ли у устройства ADB-интерфейс (class 0xFF / subclass 0x42 / protocol 0x01);
 *  4. удаётся ли получить доступ и захватить (claim) этот интерфейс;
 *  5. какие аппаратные декодеры H.264/H.265/AV1 есть на ТВ.
 *
 * UI строится кодом, без XML и без библиотек, чтобы приложение было максимально лёгким.
 */
class MainActivity : Activity() {

    private lateinit var usb: UsbManager
    private lateinit var output: TextView
    private lateinit var scrollView: ScrollView

    // Пока выбор приложения — простой перебор нескольких готовых (полный список игр с телефона — этап 4)
    private val presetApps = listOf(
        "Telegram" to "org.telegram.messenger",
        "Genshin Impact" to "com.miHoYo.GenshinImpact",
        "Play Маркет" to "com.android.vending",
        "Настройки" to "com.android.settings"
    )
    private var presetIndex = 0

    private fun startPlayer(newDisplay: Boolean) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_NEW_DISPLAY, newDisplay)
                .putExtra(PlayerActivity.EXTRA_APP, presetApps[presetIndex].second)
        )
    }

    private val actionPermission = "com.example.usbdisplay.tester.USB_PERMISSION"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED,
                actionPermission -> refresh()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        setContentView(buildUi())

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(actionPermission)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        refresh()
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101418"))
            setPadding(48, 32, 48, 32)
        }

        // Главное: приложение на виртуальном экране 16:9
        val main = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnPlay2 = Button(this).apply {
            text = "▶ Запустить на ТВ"
            textSize = 20f
            setOnClickListener { startPlayer(true) }
        }
        val btnApp = Button(this).apply {
            text = "Приложение: ${presetApps[presetIndex].first}"
            setOnClickListener {
                presetIndex = (presetIndex + 1) % presetApps.size
                text = "Приложение: ${presetApps[presetIndex].first}"
            }
        }
        main.addView(btnPlay2)
        main.addView(btnApp)
        root.addView(main)

        // Диагностика
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnRefresh = Button(this).apply {
            text = "Обновить"
            setOnClickListener { refresh() }
        }
        val btnTest = Button(this).apply {
            text = "Тест доступа"
            setOnClickListener { runAccessTest() }
        }
        val btnAdb = Button(this).apply {
            text = "Тест ADB"
            setOnClickListener { runAdbTest() }
        }
        val btnMirror = Button(this).apply {
            text = "Зеркало (проверка)"
            setOnClickListener { startPlayer(false) }
        }
        buttons.addView(btnRefresh)
        buttons.addView(btnTest)
        buttons.addView(btnAdb)
        buttons.addView(btnMirror)
        root.addView(buttons)

        val buttons2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnProbe = Button(this).apply {
            text = "Видео-проба: зеркало"
            setOnClickListener { runVideoProbe(false) }
        }
        val btnProbe2 = Button(this).apply {
            text = "Видео-проба: экран 16:9"
            setOnClickListener { runVideoProbe(true) }
        }
        buttons2.addView(btnProbe)
        buttons2.addView(btnProbe2)
        root.addView(buttons2)

        output = TextView(this).apply {
            setTextColor(Color.parseColor("#E0E0E0"))
            typeface = Typeface.MONOSPACE
            textSize = 15f
            setTextIsSelectable(false)
        }
        scrollView = ScrollView(this).apply {
            isFocusable = true          // чтобы пультом можно было листать
            isFocusableInTouchMode = true
            addView(output)
        }
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        root.gravity = Gravity.TOP
        btnPlay2.requestFocus()
        return root
    }

    // ---------------------------------------------------------------- отчёт

    private fun refresh() {
        val sb = StringBuilder()
        sb.appendLine("=== USB-тестер для ТВ (v0.7) ===")
        sb.appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine()

        val hostFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
        sb.appendLine("[1] Система заявляет USB host: ${if (hostFeature) "ДА" else "НЕТ"}")
        sb.appendLine()

        val devices = usb.deviceList.values.toList()
        sb.appendLine("[2] Подключённые USB-устройства: ${devices.size}")
        if (devices.isEmpty()) {
            sb.appendLine("    (пусто) Подключите планшет через хаб, на планшете включите")
            sb.appendLine("    отладку по USB и режим «Передача файлов».")
        }
        for (d in devices) sb.append(describe(d))
        sb.appendLine()

        sb.appendLine("[3] Аппаратные декодеры видео на этом ТВ:")
        sb.append(describeDecoders())

        output.text = sb.toString()
    }

    private fun describe(d: UsbDevice): String {
        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("  Устройство: ${d.deviceName}")
        sb.appendLine("    VID:PID = %04X:%04X".format(d.vendorId, d.productId))
        try {
            sb.appendLine("    Производитель: ${d.manufacturerName ?: "?"}")
            sb.appendLine("    Продукт:       ${d.productName ?: "?"}")
        } catch (e: SecurityException) {
            sb.appendLine("    Названия скрыты (нужно разрешение)")
        }
        sb.appendLine("    Доступ выдан: ${if (usb.hasPermission(d)) "да" else "нет"}")
        sb.appendLine("    Версия USB: ${usbVersion(d)}")

        var adbFound = false
        for (i in 0 until d.interfaceCount) {
            val itf = d.getInterface(i)
            val tag = if (isAdb(itf)) { adbFound = true; "  <-- ADB" } else ""
            sb.appendLine(
                "    interface #%d class=0x%02X sub=0x%02X proto=0x%02X eps=%d%s".format(
                    itf.id, itf.interfaceClass, itf.interfaceSubclass,
                    itf.interfaceProtocol, itf.endpointCount, tag
                )
            )
        }
        sb.appendLine("    ADB-интерфейс найден: ${if (adbFound) "ДА" else "нет"}")
        if (isAoaDevice(d)) sb.appendLine("    Это устройство в режиме Android Accessory (AOA)")
        return sb.toString()
    }

    private fun usbVersion(d: UsbDevice): String =
        if (Build.VERSION.SDK_INT >= 31) (d.version ?: "?") else "?"

    private fun isAdb(i: UsbInterface) =
        i.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
            i.interfaceSubclass == 0x42 && i.interfaceProtocol == 0x01

    // Google VID 0x18D1, PID 0x2D00..0x2D05 — режим Accessory
    private fun isAoaDevice(d: UsbDevice) =
        d.vendorId == 0x18D1 && d.productId in 0x2D00..0x2D05

    private fun describeDecoders(): String {
        val sb = StringBuilder()
        val wanted = listOf(
            MediaFormat.MIMETYPE_VIDEO_AVC to "H.264",
            MediaFormat.MIMETYPE_VIDEO_HEVC to "H.265",
            "video/av01" to "AV1"
        )
        try {
            val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            for ((mime, name) in wanted) {
                val list = infos.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
                if (list.isEmpty()) {
                    sb.appendLine("  $name: декодера нет")
                    continue
                }
                for (info in list) {
                    val caps = info.getCapabilitiesForType(mime)
                    val hw = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else null
                    val lowLat = if (Build.VERSION.SDK_INT >= 30)
                        caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
                    else null
                    val fps = try {
                        caps.videoCapabilities.getSupportedFrameRatesFor(1920, 1080).upper
                    } catch (e: Exception) { null }
                    sb.appendLine(
                        "  $name: ${info.name}  hw=${hw ?: "?"}  low-latency=${lowLat ?: "?"}  1080p до ${fps ?: "?"} fps"
                    )
                }
            }
        } catch (e: Exception) {
            sb.appendLine("  Ошибка чтения списка кодеков: ${e.message}")
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- тест доступа

    /**
     * Для каждого устройства с ADB-интерфейсом: запрашиваем разрешение,
     * открываем соединение и пытаемся захватить интерфейс. Это ровно то,
     * что понадобится будущему приложению, чтобы говорить с планшетом по ADB.
     */
    private fun runAccessTest() {
        val sb = StringBuilder(output.text).appendLine().appendLine("=== ТЕСТ ДОСТУПА К ADB ===")
        val devices = usb.deviceList.values.toList()
        if (devices.isEmpty()) {
            sb.appendLine("Нет подключённых USB-устройств.")
            output.text = sb.toString()
            return
        }

        var needPermission = false
        for (d in devices) {
            val adb = (0 until d.interfaceCount).map { d.getInterface(it) }.firstOrNull { isAdb(it) }
            val title = "%04X:%04X".format(d.vendorId, d.productId)
            if (adb == null) {
                sb.appendLine("$title: ADB-интерфейса нет, пропускаю")
                continue
            }
            if (!usb.hasPermission(d)) {
                sb.appendLine("$title: нет доступа, запрашиваю разрешение (подтвердите на ТВ)...")
                requestPermission(d)
                needPermission = true
                continue
            }
            val conn = usb.openDevice(d)
            if (conn == null) {
                sb.appendLine("$title: openDevice() вернул null — ТВ не дал открыть устройство")
                continue
            }
            val claimed = conn.claimInterface(adb, true)
            sb.appendLine("$title: openDevice OK, claimInterface = ${if (claimed) "УСПЕХ" else "ОШИБКА"}")
            for (e in 0 until adb.endpointCount) {
                val ep = adb.getEndpoint(e)
                val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                sb.appendLine("    endpoint $dir, maxPacket=${ep.maxPacketSize}")
            }
            if (claimed) {
                sb.appendLine("    => ТВ МОЖЕТ быть ADB-хостом по USB. Проект осуществим.")
                conn.releaseInterface(adb)
            }
            conn.close()
        }
        if (needPermission) sb.appendLine("После выдачи разрешения нажмите тест ещё раз.")
        output.text = sb.toString()
    }

    // ---------------------------------------------------------------- тест ADB (этап 3а)

    private fun say(text: String) {
        runOnUiThread {
            output.append("\n" + text)
            scrollView.post { scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    /**
     * Находит телефон с ADB-интерфейсом, подключается по USB (рукопожатие + авторизация RSA)
     * и выполняет [block] в фоновом потоке. Если на телефоне появится окно
     * «Разрешить отладку по USB?» — нужно подтвердить (лучше с галочкой «Всегда разрешать»).
     */
    private fun withAdb(title: String, block: (AdbUsbConnection) -> Unit) {
        output.append("\n\n=== $title ===")
        val device = usb.deviceList.values.firstOrNull { dev ->
            (0 until dev.interfaceCount).any { isAdb(dev.getInterface(it)) }
        }
        if (device == null) {
            say("Нет устройства с ADB-интерфейсом. Подключите телефон, включите отладку по USB.")
            return
        }
        if (!usb.hasPermission(device)) {
            say("Нет доступа к USB-устройству, запрашиваю (подтвердите на ТВ). Потом нажмите кнопку ещё раз.")
            requestPermission(device)
            return
        }
        val itf = (0 until device.interfaceCount).map { device.getInterface(it) }.first { isAdb(it) }

        Thread {
            var adb: AdbUsbConnection? = null
            try {
                val raw = usb.openDevice(device) ?: throw IOException("openDevice() вернул null")
                val key = AdbKey.loadOrCreate(applicationContext)
                adb = AdbUsbConnection(raw, itf, key)
                say("Подключаюсь... Если на телефоне появилось окно «Разрешить отладку по USB?», нажмите «Разрешить».")
                val t0 = System.currentTimeMillis()
                adb.connect(onWaitingForUser = {
                    say("Телефон ждёт подтверждения. Подтвердите на экране телефона (до 2 минут).")
                })
                say("ПОДКЛЮЧЕНО за ${System.currentTimeMillis() - t0} мс")
                block(adb)
            } catch (e: Exception) {
                say("ОШИБКА: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                try {
                    adb?.close()
                } catch (e: Exception) {
                    // игнорируем ошибки закрытия
                }
            }
        }.start()
    }

    private fun runAdbTest() = withAdb("ТЕСТ ADB (подключение и shell)") { adb ->
        say("Баннер: ${adb.banner}")
        val info = adb.shell(
            "getprop ro.product.model; getprop ro.build.version.release; " +
                "getprop ro.product.cpu.abi; wm size; echo ADB_OK"
        )
        say("Ответ телефона:\n$info")
        if (info.contains("ADB_OK")) {
            say("=> ADB по USB РАБОТАЕТ. Можно переходить к запуску видеосервера.")
        }
    }

    private fun runVideoProbe(newDisplay: Boolean) = withAdb(
        if (newDisplay) "ВИДЕО-ПРОБА (экран 16:9, Telegram)" else "ВИДЕО-ПРОБА (зеркало экрана телефона)"
    ) { adb ->
        ScrcpyProbe(adb, applicationContext) { say(it) }.run(newDisplay)
        say("=== проба завершена ===")
    }

    private fun requestPermission(d: UsbDevice) {
        // Android 14+: PendingIntent для USB-разрешения должен быть изменяемым и с явным пакетом
        val intent = Intent(actionPermission).setPackage(packageName)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, intent, flags))
    }
}
