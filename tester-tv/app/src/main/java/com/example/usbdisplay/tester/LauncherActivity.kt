package com.example.usbdisplay.tester

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.text.format.DateUtils
import android.view.Window
import android.graphics.Outline
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.Switch
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.usbdisplay.adb.AdbConnector
import com.example.usbdisplay.adb.AdbUsbConnection
import com.example.usbdisplay.adb.UsbLock
import com.example.usbdisplay.apps.AutoTune
import com.example.usbdisplay.apps.AppCategory
import com.example.usbdisplay.apps.Categorizer
import com.example.usbdisplay.apps.IconFetcher
import com.example.usbdisplay.apps.PhoneApp
import com.example.usbdisplay.apps.PhoneInstaller
import com.example.usbdisplay.apps.PhoneAppsLoader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Главный экран: плитки приложений и игр с телефона по категориям.
 * Выбор плитки запускает приложение на виртуальном экране 16:9 (PlayerActivity).
 * Дизайн в духе Material You (тёмная тема, тональные цвета, скруглённые плитки), без библиотек.
 */
class LauncherActivity : Activity() {

    private val actionPermission = "com.example.usbdisplay.tester.LAUNCHER_USB_PERMISSION"

    private lateinit var usb: UsbManager
    private lateinit var status: TextView
    private lateinit var chipRow: LinearLayout
    private lateinit var grid: GridLayout
    private lateinit var scroll: ScrollView
    private val ui = Handler(Looper.getMainLooper())
    private val loading = AtomicBoolean(false)

    private var apps: List<PhoneApp> = emptyList()
    private var category = AppCategory.ALL
    private lateinit var openPill: TextView
    private val prefs by lazy { getSharedPreferences("launcher", Context.MODE_PRIVATE) }

    // избранное и список запущенных нами приложений хранятся в настройках
    private fun getSet(key: String): MutableSet<String> = HashSet(prefs.getStringSet(key, emptySet()) ?: emptySet())
    private fun putSet(key: String, v: Set<String>) = prefs.edit().putStringSet(key, v).apply()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == actionPermission) loadFromPhone()
        }
    }

    // ---- цвета (тёмная схема Material 3) ----
    private val cSurface = Color.parseColor("#101418")
    private val cOnSurface = Color.parseColor("#E1E2E8")
    private val cMuted = Color.parseColor("#A9ADB5")
    private val cChip = Color.parseColor("#2B3038")
    private val cChipSel = Color.parseColor("#3D5A80")
    private val cChipFocus = Color.parseColor("#4A6A94")

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Одно приложение на всё: на телефоне/планшете оно работает как тачпад для ТВ
        val isTv = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        if (!isTv) {
            startActivity(Intent(this, PadActivity::class.java))
            finish()
            return
        }
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        setContentView(buildUi())

        val filter = IntentFilter(actionPermission)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }

        apps = PhoneAppsLoader.loadCache(applicationContext)
        category = try {
            AppCategory.valueOf(prefs.getString("category", AppCategory.ALL.name) ?: AppCategory.ALL.name)
        } catch (e: IllegalArgumentException) {
            AppCategory.ALL
        }
        render()
        updateOpenPill()
        if (apps.isEmpty()) loadFromPhone() else setStatus("Приложений: ${apps.size}. «Обновить» — перечитать список с телефона.")
    }

    override fun onResume() {
        super.onResume()
        // после возврата из трансляции проверяем, какие приложения ещё живы на телефоне
        if (::usb.isInitialized && apps.isNotEmpty()) refreshOpen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // телефон подключили к уже открытому приложению
        if (::usb.isInitialized && apps.isEmpty()) loadFromPhone()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // приёмник не регистрировали (режим телефона)
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------ интерфейс

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cSurface)
            setPadding(dp(40), dp(24), dp(40), dp(16))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = "ДопЭкран"
            setTextColor(cOnSurface)
            textSize = 30f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        openPill = pill("Открыто: 0", false) { closeAll() }
        top.addView(openPill)
        top.addView(pill("Обновить", false) { loadFromPhone() })
        top.addView(pill("Диагностика", false) { startActivity(Intent(this, MainActivity::class.java)) })
        root.addView(top)

        status = TextView(this).apply {
            setTextColor(cMuted)
            textSize = 14f
            setPadding(0, dp(6), 0, dp(10))
        }
        root.addView(status)

        chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow)
        }
        root.addView(chipScroll)

        grid = GridLayout(this)
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, dp(12), 0, dp(24))
            addView(grid)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun stateBg(normal: Int, focused: Int, radiusDp: Int): StateListDrawable {
        val r = dp(radiusDp).toFloat()
        val f = GradientDrawable().apply {
            setColor(focused)
            cornerRadius = r
            setStroke(dp(3), Color.WHITE)
        }
        val n = GradientDrawable().apply {
            setColor(normal)
            cornerRadius = r
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), f)
            addState(intArrayOf(), n)
        }
    }

    /** Кнопка-«таблетка» (чип). */
    private fun pill(text: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(cOnSurface)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = stateBg(if (selected) cChipSel else cChip, cChipFocus, 100)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 0, dp(10), 0) }
        }

    private fun categoryOf(a: PhoneApp) = Categorizer.of(a)

    private fun render() {
        // чипы категорий: только непустые
        chipRow.removeAllViews()
        val favs = getSet("fav")
        val counts = apps.groupingBy { categoryOf(it) }.eachCount()
        val list = AppCategory.values().filter { c ->
            when (c) {
                AppCategory.ALL -> apps.any { !it.system }
                AppCategory.FAVORITES -> apps.any { it.pkg in favs }
                else -> (counts[c] ?: 0) > 0
            }
        }
        if (category !in list && list.isNotEmpty()) category = if (AppCategory.GAMES in list) AppCategory.GAMES else list.first()
        for (c in list) {
            chipRow.addView(pill(c.title, c == category) {
                category = c
                prefs.edit().putString("category", c.name).apply()
                render()
            })
        }

        // плитки
        grid.removeAllViews()
        val shown = when (category) {
            AppCategory.ALL -> apps.filter { !it.system }
            AppCategory.FAVORITES -> apps.filter { it.pkg in favs }
            else -> apps.filter { categoryOf(it) == category }
        }
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val cols = ((widthDp - 80) / 190).toInt().coerceIn(3, 8)
        grid.columnCount = cols
        for (a in shown) grid.addView(tile(a))
        if (shown.isEmpty()) {
            grid.addView(TextView(this).apply {
                text = "Здесь пока пусто. Подключите телефон и нажмите «Обновить»."
                setTextColor(cMuted)
                textSize = 16f
            })
        }
        // первый элемент получает фокус, чтобы пультом сразу можно было выбирать
        grid.post { grid.getChildAt(0)?.requestFocus() }
    }

    private fun tile(a: PhoneApp): View {
        val hue = (Math.abs(a.pkg.hashCode()) % 360).toFloat()
        val base = Color.HSVToColor(floatArrayOf(hue, 0.30f, 0.26f))
        val focus = Color.HSVToColor(floatArrayOf(hue, 0.35f, 0.38f))
        val accent = Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.70f))

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(16), dp(12), dp(14))
            background = stateBg(base, focus, 28)
            isFocusable = true
            isClickable = true
            setOnClickListener { showLaunchCard(a) }
            // долгое нажатие OK (или долгий тап): меню приложения
            setOnLongClickListener {
                showMenu(a)
                true
            }
        }
        val icon = IconFetcher.get(applicationContext, a.pkg)
        if (icon != null) {
            val iv = ImageView(this).apply {
                setImageBitmap(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.26f)
                    }
                }
                clipToOutline = true
            }
            box.addView(iv, LinearLayout.LayoutParams(dp(64), dp(64)))
        } else {
            val letter = TextView(this).apply {
                text = a.label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
                setTextColor(Color.parseColor("#101418"))
                textSize = 26f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accent)
                }
            }
            box.addView(letter, LinearLayout.LayoutParams(dp(60), dp(60)))
        }
        box.addView(TextView(this).apply {
            text = a.label
            setTextColor(cOnSurface)
            textSize = 15f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val lp = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f))
        lp.width = 0
        lp.height = dp(138)
        lp.setMargins(dp(6), dp(6), dp(6), dp(6))
        box.layoutParams = lp
        return box
    }

    private fun setStatus(t: String) = ui.post { status.text = t }

    // ------------------------------------------------------------ запуск и загрузка

    /** Компактная кнопка-переключатель или кнопка выбора для карточки запуска (ширина делится поровну в строке). */
    private fun chip(text: String, selected: Boolean, onClick: (TextView) -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(cOnSurface)
            textSize = 15f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = stateBg(if (selected) cChipSel else cChip, cChipFocus, 100)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick(this) }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.setMargins(dp(4), dp(4), dp(4), dp(4)) }
        }

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) addView(v)
    }

    /**
     * Карточка запуска. Слева — иконка, название, последний запуск и кнопки «Запустить» / «В избранное» / «Отмена»;
     * справа — компактные переключатели (всё помещается на экране ТВ без прокрутки).
     */
    private fun showLaunchCard(a: PhoneApp) {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)

        // ------ левая колонка
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val icon = IconFetcher.get(applicationContext, a.pkg)
        if (icon != null) {
            left.addView(ImageView(this).apply {
                setImageBitmap(icon)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.26f)
                    }
                }
                clipToOutline = true
            }, LinearLayout.LayoutParams(dp(88), dp(88)))
        } else {
            val hue = (Math.abs(a.pkg.hashCode()) % 360).toFloat()
            left.addView(TextView(this).apply {
                text = a.label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
                setTextColor(Color.parseColor("#101418"))
                textSize = 36f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.70f)))
                }
            }, LinearLayout.LayoutParams(dp(88), dp(88)))
        }
        left.addView(TextView(this).apply {
            text = a.label
            setTextColor(cOnSurface)
            textSize = 20f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(10), 0, dp(2))
        })
        val last = prefs.getLong("last_" + a.pkg, 0L)
        left.addView(TextView(this).apply {
            text = if (last == 0L) "Ещё не запускалось" else "Запуск: " +
                DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            setTextColor(cMuted)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        })

        fun favText() = if (a.pkg in getSet("fav")) "★ В избранном" else "☆ В избранное"
        val start = chip("▶ Запустить", true) {
            d.dismiss()
            launch(a)
        }
        val fav = chip(favText(), false) { v ->
            val f = getSet("fav")
            if (a.pkg in f) f.remove(a.pkg) else f.add(a.pkg)
            putSet("fav", f)
            v.text = favText()
            render()
        }
        val cancel = chip("Отмена", false) { d.dismiss() }
        for (b in listOf(start, fav, cancel)) {
            b.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.setMargins(0, dp(4), 0, dp(4)) }
            left.addView(b)
        }

        // ------ правая колонка: переключатели
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun mark(on: Boolean, label: String) = (if (on) "✓ " else "○ ") + label
        fun toggle(label: String, key: String, def: Boolean, onChange: (Boolean) -> Unit = {}): TextView {
            val on0 = prefs.getBoolean(key, def)
            return chip(mark(on0, label), on0) { v ->
                val on = !prefs.getBoolean(key, def)
                prefs.edit().putBoolean(key, on).apply()
                v.text = mark(on, label)
                v.background = stateBg(if (on) cChipSel else cChip, cChipFocus, 100)
                onChange(on)
            }
        }
        fun setToggle(v: TextView, label: String, key: String, on: Boolean) {
            prefs.edit().putBoolean(key, on).apply()
            v.text = mark(on, label)
            v.background = stateBg(if (on) cChipSel else cChip, cChipFocus, 100)
        }

        // Тачпад и «выключить экран» взаимоисключающие (можно выключить оба)
        if (prefs.getBoolean("opt_pad", true) && prefs.getBoolean("opt_off", false)) prefs.edit().putBoolean("opt_off", false).apply()
        lateinit var tPad: TextView
        lateinit var tOff: TextView
        tPad = toggle("Тачпад на телефоне", "opt_pad", true) { on -> if (on) setToggle(tOff, "Экран телефона выкл.", "opt_off", false) }
        tOff = toggle("Экран телефона выкл.", "opt_off", false) { on -> if (on) setToggle(tPad, "Тачпад на телефоне", "opt_pad", false) }
        right.addView(row(tPad, tOff))
        right.addView(row(toggle("Производительность", "opt_perf", false), toggle("Звук на ТВ", "opt_audio", true)))

        // Адаптивный битрейт + качество
        val brValues = listOf(5_000_000, 8_000_000, 10_000_000, 14_000_000)
        val resNames = listOf("720p", "1080p", "1440p")
        val autoInfo = TextView(this).apply {
            setTextColor(cMuted)
            textSize = 12f
            setPadding(dp(8), dp(2), dp(8), dp(4))
        }
        lateinit var brChip: TextView
        fun refreshAuto() {
            val auto = prefs.getBoolean("opt_auto", true)
            val br = if (auto) AutoTune.bitrateFor(prefs, a.pkg) else prefs.getInt("opt_br", 10_000_000)
            val est = AutoTune.estFps(prefs, a.pkg)
            autoInfo.text = if (!auto) "Битрейт задан вручную."
            else if (est > 0f) "Авто: ≈ ${est.toInt()} к/с при ${"%.1f".format(br / 1e6)} Мбит/с (по прошлым запускам)"
            else "Авто: первый запуск, начнём с ${AutoTune.START_BITRATE / 1_000_000} Мбит/с и запомним лучшее"
            brChip.visibility = if (auto) View.INVISIBLE else View.VISIBLE
            brChip.text = "Битрейт: ${br / 1_000_000} Мбит/с"
        }
        brChip = chip("", false) {
            val cur = prefs.getInt("opt_br", 10_000_000)
            prefs.edit().putInt("opt_br", brValues[(brValues.indexOf(cur) + 1) % brValues.size]).apply()
            refreshAuto()
        }
        val tAuto = toggle("Адаптивный битрейт", "opt_auto", true) { refreshAuto() }
        right.addView(row(tAuto, brChip))
        right.addView(autoInfo)

        val latNames = listOf("Баланс", "Минимум")
        val latChip = chip("Задержка: ${latNames[prefs.getInt("opt_lat", 0)]}", false) { v ->
            val n = 1 - prefs.getInt("opt_lat", 0)
            prefs.edit().putInt("opt_lat", n).apply()
            v.text = "Задержка: ${latNames[n]}"
        }
        val resChip = chip("Экран: ${resNames[prefs.getInt("opt_res", 1)]}", false) { v ->
            val n = (prefs.getInt("opt_res", 1) + 1) % resNames.size
            prefs.edit().putInt("opt_res", n).apply()
            v.text = "Экран: ${resNames[n]}"
        }
        val fpsChip = chip("Кадры: ${prefs.getInt("opt_fps", 60)}", false) { v ->
            val n = if (prefs.getInt("opt_fps", 60) == 60) 30 else 60
            prefs.edit().putInt("opt_fps", n).apply()
            v.text = "Кадры: $n"
        }
        right.addView(row(latChip, resChip, fpsChip))

        val pktValues = listOf(256, 128, 64, 32, 16)
        val pktChip = chip("Пакет ADB: ${prefs.getInt("opt_pkt", 256)} КБ", false) { v ->
            val cur = prefs.getInt("opt_pkt", 256)
            val n = pktValues[(pktValues.indexOf(cur) + 1) % pktValues.size]
            prefs.edit().putInt("opt_pkt", n).apply()
            v.text = "Пакет ADB: $n КБ"
        }
        right.addView(row(pktChip))
        refreshAuto()
        right.addView(TextView(this).apply {
            text = "Тачпад и выключенный экран несовместимы: сенсор телефона нужен для тачпада. Можно выключить оба."
            setTextColor(cMuted)
            textSize = 12f
            setPadding(dp(8), dp(8), dp(8), 0)
        })

        // ------ карточка целиком
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1D232B"))
                cornerRadius = dp(28).toFloat()
            }
        }
        card.addView(left, LinearLayout.LayoutParams(dp(200), ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
            it.setMargins(dp(20), 0, 0, 0)
        })

        // На всякий случай внутри прокрутки: если экран совсем маленький, до кнопок можно долистать стрелками
        val scrollView = ScrollView(this).apply { addView(card) }
        d.setContentView(scrollView)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val w = (resources.displayMetrics.widthPixels * 0.86f).toInt().coerceAtMost(dp(940))
        d.window?.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        d.show()
        start.requestFocus()
    }

    private fun launch(a: PhoneApp) {
        putSet("launched", getSet("launched").also { it.add(a.pkg) })
        prefs.edit().putLong("last_" + a.pkg, System.currentTimeMillis()).apply()
        val auto = prefs.getBoolean("opt_auto", true)
        val bitrate = if (auto) AutoTune.bitrateFor(prefs, a.pkg) else prefs.getInt("opt_br", 10_000_000)
        val (w, h) = when (prefs.getInt("opt_res", 1)) {
            0 -> 1280 to 720
            2 -> 2560 to 1440
            else -> 1920 to 1080
        }
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_NEW_DISPLAY, true)
                .putExtra(PlayerActivity.EXTRA_APP, a.pkg)
                .putExtra(PlayerActivity.EXTRA_PAD, prefs.getBoolean("opt_pad", true))
                .putExtra(PlayerActivity.EXTRA_SCREEN_OFF, prefs.getBoolean("opt_off", false))
                .putExtra(PlayerActivity.EXTRA_PERF, prefs.getBoolean("opt_perf", false))
                .putExtra(PlayerActivity.EXTRA_AUTOTUNE, auto)
                .putExtra(PlayerActivity.EXTRA_BITRATE, bitrate)
                .putExtra(PlayerActivity.EXTRA_FPS, prefs.getInt("opt_fps", 60))
                .putExtra(PlayerActivity.EXTRA_LATENCY, prefs.getInt("opt_lat", 0))
                .putExtra(PlayerActivity.EXTRA_AUDIO, prefs.getBoolean("opt_audio", true))
                .putExtra(PlayerActivity.EXTRA_PKT, prefs.getInt("opt_pkt", 256))
                .putExtra(PlayerActivity.EXTRA_W, w)
                .putExtra(PlayerActivity.EXTRA_H, h)
        )
    }

    /** Подключается к телефону по USB в фоне и выполняет block; одновременно работает только одно такое действие. */
    private fun withPhone(statusText: String?, quiet: Boolean, block: (AdbUsbConnection) -> Unit) {
        if (!loading.compareAndSet(false, true)) return
        val dev = AdbConnector.findDevice(usb)
        if (dev == null) {
            if (!quiet) setStatus("Телефон не найден. Подключите его кабелем и включите отладку по USB.")
            loading.set(false)
            return
        }
        if (!usb.hasPermission(dev)) {
            if (!quiet) {
                setStatus("Подтвердите доступ к USB-устройству на экране ТВ…")
                requestPermission(dev)
            }
            loading.set(false)
            return
        }
        if (statusText != null) setStatus(statusText)
        Thread {
            if (!UsbLock.acquire(25_000)) {
                if (!quiet) setStatus("USB занят другой операцией, попробуйте ещё раз")
                loading.set(false)
                return@Thread
            }
            var adb: AdbUsbConnection? = null
            try {
                adb = AdbConnector.connect(applicationContext, usb, dev) {
                    setStatus("Подтвердите отладку по USB на экране телефона (до 2 минут)")
                }
                block(adb)
            } catch (e: Exception) {
                if (!quiet) setStatus("Ошибка: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                try {
                    adb?.close()
                } catch (e: Exception) {
                    // уже закрыто
                }
                UsbLock.release()
                loading.set(false)
            }
        }.start()
    }

    private fun loadFromPhone() = withPhone("Читаю список приложений с телефона…", false) { adb ->
        // сначала ставим/обновляем приложение на самом телефоне (нужно для тачпада)
        var onPhone = false
        try {
            onPhone = PhoneInstaller.ensure(applicationContext, adb) { setStatus(it) }
        } catch (e: Exception) {
            setStatus("Установка на телефон не удалась: ${e.message}")
        }
        val list = PhoneAppsLoader.load(adb, applicationContext) { setStatus(it) }
        if (onPhone) {
            setStatus("Загружаю иконки приложений…")
            try {
                IconFetcher.fetch(applicationContext, adb) { setStatus(it) }
            } catch (e: Exception) {
                setStatus("Иконки не загрузились: ${e.message}")
            }
        }
        PhoneAppsLoader.saveCache(applicationContext, list)
        checkAlive(adb)
        ui.post {
            apps = list
            render()
            status.text = "Приложений: ${list.size}"
        }
    }

    // ------------------------------------------------------------ открытые приложения

    private fun updateOpenPill() {
        openPill.text = "Открыто: ${getSet("launched").size}  ✕ закрыть все"
    }

    /** Оставляет в списке только те приложения, процессы которых ещё живы на телефоне. */
    private fun checkAlive(adb: AdbUsbConnection) {
        val alive = HashSet<String>()
        for (pkg in getSet("launched")) {
            val out = adb.shell("pidof $pkg", 4000).trim()
            when {
                out.isEmpty() -> {}                              // процесса нет
                out.matches(Regex("[0-9 ]+")) -> alive.add(pkg)  // живой
                else -> alive.add(pkg)                           // pidof недоступен: не знаем, оставляем
            }
        }
        putSet("launched", alive)
        ui.post { updateOpenPill() }
    }

    private fun refreshOpen() = withPhone(null, true) { adb -> checkAlive(adb) }

    private fun closeAll() {
        if (getSet("launched").isEmpty()) {
            setStatus("Открытых приложений нет")
            return
        }
        withPhone("Закрываю приложения на телефоне…", false) { adb ->
            val n = closePackages(adb, getSet("launched"))
            ui.post {
                updateOpenPill()
                status.text = "Закрыто приложений: $n"
            }
        }
    }

    private fun closePackages(adb: AdbUsbConnection, pkgs: Set<String>): Int {
        for (p in pkgs) adb.shell("am force-stop $p", 5000)
        putSet("launched", getSet("launched").also { it.removeAll(pkgs) })
        return pkgs.size
    }

    // ------------------------------------------------------------ меню приложения

    private fun showMenu(a: PhoneApp) {
        val fav = a.pkg in getSet("fav")
        val items = arrayOf(
            if (fav) "Убрать из избранного" else "Добавить в избранное",
            "Закрыть приложение на телефоне"
        )
        AlertDialog.Builder(this)
            .setTitle(a.label)
            .setItems(items) { _, which ->
                if (which == 0) {
                    val f = getSet("fav")
                    if (fav) f.remove(a.pkg) else f.add(a.pkg)
                    putSet("fav", f)
                    render()
                } else {
                    withPhone("Закрываю ${a.label}…", false) { adb ->
                        closePackages(adb, setOf(a.pkg))
                        ui.post {
                            updateOpenPill()
                            status.text = "${a.label} закрыто"
                        }
                    }
                }
            }
            .show()
    }

    private fun requestPermission(d: UsbDevice) {
        val i = Intent(actionPermission).setPackage(packageName)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        usb.requestPermission(d, PendingIntent.getBroadcast(this, 1, i, flags))
    }
}
