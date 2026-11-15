package com.example.usbdisplay.apps

import android.content.Context
import com.example.usbdisplay.adb.AdbUsbConnection
import com.example.usbdisplay.scrcpy.ScrcpySession

/** Приложение на телефоне, которое можно запустить на виртуальном экране. */
data class PhoneApp(val pkg: String, val label: String, val system: Boolean)

enum class AppCategory(val title: String) {
    FAVORITES("★ Избранное"),
    ALL("Все"),
    GAMES("Игры"),
    VIDEO("Видео"),
    SOCIAL("Общение"),
    APPS("Приложения"),
    SYSTEM("Система")
}

/** Грубое определение категории по имени пакета (настоящих категорий по ADB не получить без лишних затрат). */
object Categorizer {
    private val games = listOf(
        "mihoyo", "hoyoverse", "supercell", "mojang", "activision", "pubg", "garena", "roblox", "netease",
        "ubisoft", "gameloft", "playrix", "nianticlabs", "zeptolab", "rovio", "innersloth", "kitkagames",
        "tencent.ig", "tencent.tmgp", "lilithgames", "habby", "voodoo", "ea.game", "king.", "unity", "game",
        "brawl", "clash", "legends", "mobile.legends", "moonton", "cyou", "papergames", "sybo", "miniclip"
    )
    private val video = listOf(
        "youtube", "netflix", "kinopoisk", "ivi.", "okko", "vlc", "mxtech", "twitch", "rutube", "wink",
        "more.tv", "premier", "kion", "plex", "kodi", "disney", "hbo", "primevideo", "avod", "video"
    )
    private val social = listOf(
        "telegram", "whatsapp", "viber", "vkontakte", "com.vk.", "instagram", "facebook", "discord",
        "tiktok", "twitter", "snapchat", "skype", "zoom", "signal", "reddit", "max.messenger"
    )

    fun of(app: PhoneApp): AppCategory {
        if (app.system) return AppCategory.SYSTEM
        val p = app.pkg.lowercase()
        return when {
            games.any { p.contains(it) } -> AppCategory.GAMES
            video.any { p.contains(it) } -> AppCategory.VIDEO
            social.any { p.contains(it) } -> AppCategory.SOCIAL
            else -> AppCategory.APPS
        }
    }
}

/**
 * Читает список приложений с телефона. Основной способ — режим `list_apps=true` самого scrcpy-server
 * (он печатает «имя + пакет» всех приложений с главным экраном, системные помечены `*`).
 * Запасной — `pm list packages -3` (только установленные пользователем, без красивых имён).
 */
object PhoneAppsLoader {
    private val pkgRe = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val spaces = Regex("\\s+")

    fun load(adb: AdbUsbConnection, context: Context, log: (String) -> Unit = {}): List<PhoneApp> {
        val server = context.assets.open(ScrcpySession.ASSET_NAME).use { it.readBytes() }
        com.example.usbdisplay.scrcpy.ServerFile.ensure(adb, server)
        val out = adb.shell(
            "CLASSPATH=${ScrcpySession.REMOTE_PATH} app_process / com.genymobile.scrcpy.Server " +
                "${ScrcpySession.SERVER_VERSION} log_level=info list_apps=true",
            25_000
        )
        val parsed = parse(out, context.packageName)
        if (parsed.isNotEmpty()) return parsed

        log("list_apps не дал результата, читаю через pm. Ответ сервера: ${out.take(200)}")
        val pm = adb.shell("pm list packages -3", 10_000)
        return pm.lines().map { it.trim() }.filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .filter { it != context.packageName }
            .map { PhoneApp(it, it.substringAfterLast('.').replaceFirstChar { c -> c.uppercase() }, false) }
            .sortedBy { it.label.lowercase() }
    }

    fun parse(text: String, selfPkg: String): List<PhoneApp> {
        val res = LinkedHashMap<String, PhoneApp>()
        for (raw in text.lines()) {
            val t = raw.trim()
            if (t.isEmpty() || (t[0] != '*' && t[0] != '-')) continue
            val system = t[0] == '*'
            val tokens = t.substring(1).trim().split(spaces)
            val idx = tokens.indexOfLast { pkgRe.matches(it) }
            if (idx < 0) continue
            val pkg = tokens[idx]
            if (pkg == selfPkg) continue
            val label = tokens.subList(0, idx).joinToString(" ").ifBlank { pkg.substringAfterLast('.') }
            res[pkg] = PhoneApp(pkg, label, system)
        }
        return res.values.sortedBy { it.label.lowercase() }
    }

    // ---- кэш, чтобы плитки появлялись сразу при запуске ----

    fun saveCache(context: Context, apps: List<PhoneApp>) {
        val s = apps.joinToString("\n") { "${it.pkg}\t${it.label}\t${if (it.system) 1 else 0}" }
        context.getSharedPreferences("apps", Context.MODE_PRIVATE).edit().putString("list", s).apply()
    }

    fun loadCache(context: Context): List<PhoneApp> {
        val s = context.getSharedPreferences("apps", Context.MODE_PRIVATE).getString("list", null) ?: return emptyList()
        return s.lines().mapNotNull {
            val p = it.split('\t')
            if (p.size == 3) PhoneApp(p[0], p[1], p[2] == "1") else null
        }
    }
}
