package com.example.usbdisplay.apps

import android.content.SharedPreferences

/**
 * Адаптивный битрейт по результатам прошлых запусков каждого приложения.
 * У scrcpy-server нет команды «сменить битрейт на лету», а перезапуск сервера уничтожил бы виртуальный экран
 * вместе с игрой, поэтому подбор идёт между запусками: во время трансляции собирается статистика
 * (затор в очереди, реальные кадры в секунду, лишняя задержка), при выходе по ней уточняется битрейт.
 */
object AutoTune {
    const val START_BITRATE = 8_000_000
    private const val MIN = 3_000_000
    private const val MAX = 14_000_000

    fun bitrateFor(p: SharedPreferences, pkg: String): Int = p.getInt("tune_br_$pkg", START_BITRATE)

    /** Ожидаемое число кадров в секунду в активных сценах (0 — данных ещё нет). */
    fun estFps(p: SharedPreferences, pkg: String): Float = p.getFloat("tune_fps_$pkg", 0f)

    /**
     * @param fpsSamples кадры/с за каждую секунду, когда шёл поток (> 1 Мбит/с): в статичных сценах кодер
     *                   сам почти ничего не шлёт, такие секунды не считаются
     * @param congestion сколько раз очередь разрасталась настолько, что приходилось пропускать кадры
     * @param avgDelayMs средняя лишняя задержка канала
     */
    fun update(
        p: SharedPreferences, pkg: String, used: Int, fpsCap: Int,
        fpsSamples: List<Float>, congestion: Int, avgDelayMs: Double
    ) {
        if (fpsSamples.size < 8) return // слишком короткий сеанс, выводов не делаем
        val sorted = fpsSamples.sorted()
        val p80 = sorted[(sorted.size * 0.8).toInt().coerceAtMost(sorted.size - 1)]
        val old = estFps(p, pkg)
        val est = if (old == 0f) p80 else old * 0.5f + p80 * 0.5f

        var next = used
        if (congestion >= 2) {
            next = (used * 0.75).toInt() // канал не успевает: снижаем
        } else if (p80 >= fpsCap * 0.9f && avgDelayMs < 40) {
            next = (used * 1.15).toInt() // всё идёт с запасом: пробуем качество выше
        }
        next = (next.coerceIn(MIN, MAX) / 500_000) * 500_000
        p.edit().putInt("tune_br_$pkg", next).putFloat("tune_fps_$pkg", est).apply()
    }
}
