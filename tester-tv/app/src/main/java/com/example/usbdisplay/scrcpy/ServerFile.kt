package com.example.usbdisplay.scrcpy

import com.example.usbdisplay.adb.AdbUsbConnection
import com.example.usbdisplay.adb.push

/** Загрузка scrcpy-server на телефон только если там нет такого же файла (экономит ≈ 1 секунду на каждом запуске). */
object ServerFile {
    /** true — файл загружен, false — уже был. */
    fun ensure(adb: AdbUsbConnection, server: ByteArray): Boolean {
        val out = adb.shell("stat -c %s ${ScrcpySession.REMOTE_PATH}", 4000).trim()
        if (out == server.size.toString()) return false
        adb.push(ScrcpySession.REMOTE_PATH, server)
        return true
    }
}
