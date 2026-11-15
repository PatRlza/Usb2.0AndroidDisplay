package com.example.usbdisplay.adb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

/** Поиск телефона с ADB-интерфейсом и подключение к нему (рукопожатие + авторизация). */
object AdbConnector {

    fun isAdb(i: UsbInterface): Boolean =
        i.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
            i.interfaceSubclass == 0x42 && i.interfaceProtocol == 0x01

    fun findDevice(usb: UsbManager): UsbDevice? =
        usb.deviceList.values.firstOrNull { d ->
            (0 until d.interfaceCount).any { isAdb(d.getInterface(it)) }
        }

    /** Блокирующий вызов: вызывайте из фонового потока. Бросает IOException при неудаче. */
    fun connect(
        context: Context,
        usb: UsbManager,
        device: UsbDevice,
        onWaitingForUser: () -> Unit = {}
    ): AdbUsbConnection {
        val itf = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { isAdb(it) } ?: throw IOException("У устройства нет ADB-интерфейса")
        val raw = usb.openDevice(device) ?: throw IOException("openDevice() вернул null")
        val conn = AdbUsbConnection(raw, itf, AdbKey.loadOrCreate(context))
        try {
            conn.connect(onWaitingForUser = onWaitingForUser)
        } catch (e: Exception) {
            conn.close()
            throw e
        }
        return conn
    }
}
