package com.example.usbdisplay.apps

import com.example.usbdisplay.adb.AdbUsbConnection

/**
 * Режим производительности на телефоне (всё через оболочку ADB, без root):
 *  - `am kill-all` — завершает фоновые (кэшированные) процессы, освобождая память и ядра;
 *  - `cmd game mode performance <пакет>` — игровой режим «Производительность» для приложения (Android 12+);
 *  - `cmd power set-fixed-performance-mode-enabled true` — фиксированный режим производительности (где есть).
 * После трансляции [revert] возвращает всё как было. Команды, которых нет в прошивке, просто игнорируются.
 */
object PerformanceMode {
    // Частота обновления экрана телефона: виртуальный экран собирается в такт основному, при 120 Гц кадр
    // попадает в кодер в среднем на 4 мс раньше. Ключи AOSP; на некоторых прошивках (MIUI) могут игнорироваться.
    private val rateKeys = listOf("min_refresh_rate", "peak_refresh_rate")
    private val savedRates = HashMap<String, String>()

    /** Короткий итог ответа команды: пусто — выполнено, иначе показываем ответ или «не поддерживается». */
    private fun verdict(out: String): String {
        val line = out.lines().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        if (line.isEmpty()) return "выполнено"
        val bad = listOf("unknown", "error", "exception", "not found", "no such", "invalid", "not supported")
        return if (bad.any { line.contains(it, ignoreCase = true) }) "не поддерживается прошивкой ($line)".take(100) else line.take(100)
    }

    fun apply(adb: AdbUsbConnection, pkg: String?, log: (String) -> Unit) {
        adb.shell("am kill-all", 8000)
        log("Режим производительности: фоновые процессы закрыты")
        if (pkg != null) {
            val g = adb.shell("cmd game mode performance $pkg", 6000).trim()
            log("Игровой режим «Производительность»: " + verdict(g))
        }
        savedRates.clear()
        for (k in rateKeys) {
            savedRates[k] = adb.shell("settings get system $k", 3000).trim()
            adb.shell("settings put system $k 120.0", 3000)
        }
        val now = rateKeys.joinToString(", ") { k -> "$k=" + adb.shell("settings get system $k", 3000).trim() }
        log("Частота обновления телефона, запрошено 120 Гц; сейчас: $now")
        val f = adb.shell("cmd power set-fixed-performance-mode-enabled true", 6000).trim()
        log("Фиксированная производительность: " + verdict(f))
    }

    fun revert(adb: AdbUsbConnection, pkg: String?) {
        if (pkg != null) adb.shell("cmd game mode standard $pkg", 4000)
        adb.shell("cmd power set-fixed-performance-mode-enabled false", 4000)
        for ((k, v) in savedRates) {
            if (v.isEmpty() || v == "null") adb.shell("settings delete system $k", 3000)
            else adb.shell("settings put system $k $v", 3000)
        }
        savedRates.clear()
    }
}
