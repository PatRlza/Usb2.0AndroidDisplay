package com.example.usbdisplay.apps

import android.content.Context
import com.example.usbdisplay.adb.AdbUsbConnection
import com.example.usbdisplay.adb.push
import java.io.File

/**
 * Ставит это же приложение на телефон по кабелю (ТВ берёт собственный APK и устанавливает его через ADB),
 * чтобы на телефон ничего не нужно было ставить вручную. Если на телефоне версия старее — обновляет.
 * Подпись у обоих копий одинаковая (это один и тот же файл), поэтому обновление поверх проходит.
 */
object PhoneInstaller {
    private const val REMOTE = "/data/local/tmp/dopexran.apk"

    /** true — на телефоне стоит актуальная версия (уже была или только что установили). */
    fun ensure(context: Context, adb: AdbUsbConnection, log: (String) -> Unit): Boolean {
        val pkg = context.packageName
        @Suppress("DEPRECATION")
        val mine = context.packageManager.getPackageInfo(pkg, 0).versionCode

        val out = adb.shell("dumpsys package $pkg | grep versionCode", 10_000)
        val installed = Regex("versionCode=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
        if (installed != null && installed >= mine) return true

        log(
            if (installed == null) "Устанавливаю ДопЭкран на телефон по кабелю…"
            else "Обновляю ДопЭкран на телефоне ($installed → $mine)…"
        )
        val apk = File(context.applicationInfo.sourceDir).readBytes()
        adb.push(REMOTE, apk)
        val res = adb.shell("pm install -r $REMOTE", 90_000).trim()
        adb.shell("rm $REMOTE", 4000)
        val ok = res.contains("Success")
        if (ok) {
            log("ДопЭкран установлен на телефон")
        } else {
            val last = res.lines().lastOrNull { it.isNotBlank() } ?: "нет ответа"
            val hint = if (res.contains("USER_RESTRICTED") || res.contains("INSTALL_FAILED"))
                " Включите на телефоне в «Для разработчиков» пункт «Установка через USB» и разрешите установку."
            else ""
            log("Не удалось установить на телефон: $last.$hint")
        }
        return ok
    }
}
