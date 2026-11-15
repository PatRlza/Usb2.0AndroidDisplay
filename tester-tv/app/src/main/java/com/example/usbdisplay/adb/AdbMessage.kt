package com.example.usbdisplay.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Константы протокола ADB (см. AOSP: system/adb/protocol.txt). Все числа — little-endian. */
object Adb {
    const val A_CNXN = 0x4e584e43
    const val A_AUTH = 0x48545541
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257

    const val VERSION = 0x01000000
    /** Максимальный размер данных в одном сообщении, который мы объявляем устройству. */
    const val HOST_MAX_DATA = 256 * 1024

    /**
     * Максимальный размер пакета, который мы сообщаем телефону при подключении (эксперимент по задержке:
     * меньшие пакеты быстрее доходят до видеоразборщика, но требуют больше подтверждений OKAY).
     */
    @Volatile
    var advertisedMaxData: Int = HOST_MAX_DATA

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    const val HEADER_SIZE = 24
}

class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val data: ByteArray = EMPTY
) {
    fun headerBytes(): ByteArray {
        val b = ByteBuffer.allocate(Adb.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(command)
        b.putInt(arg0)
        b.putInt(arg1)
        b.putInt(data.size)
        b.putInt(checksum(data))
        b.putInt(command xor -1)
        return b.array()
    }

    companion object {
        val EMPTY = ByteArray(0)

        fun checksum(data: ByteArray): Int {
            var sum = 0
            for (x in data) sum += (x.toInt() and 0xFF)
            return sum
        }
    }
}
