package com.example.usbdisplay.pad

/**
 * Протокол «тачпад на телефоне → ТВ» (геймпад телефона на ТВ не пересылается). Идёт по тому же USB-кабелю: приложение на телефоне слушает
 * абстрактный сокет [SOCKET], ТВ подключается к нему через ADB (`localabstract:`). Все числа big-endian.
 *
 *  1 MOVE    dx i16, dy i16   (пиксели телефона × 8: сдвиг пальца)
 *  2 TAP                      (касание одним пальцем = клик)
 *  3 BACK                     (касание тремя пальцами = «Назад»)
 *  4 DOWN                     (долгое удержание пальца: взять и тащить)
 *  5 UP                       (палец отпущен после удержания)
 *  6 SCROLL  dy i16           (два пальца: прокрутка, пиксели × 8)
 */
object PadProtocol {
    const val SOCKET = "dopexran_pad"

    const val MOVE = 1
    const val TAP = 2
    const val BACK = 3
    const val DOWN = 4
    const val UP = 5
    const val SCROLL = 6

    fun i16(v: Int): ByteArray = byteArrayOf((v shr 8).toByte(), v.toByte())
}
