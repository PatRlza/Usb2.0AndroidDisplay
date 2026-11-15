package com.example.usbdisplay.adb

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Один USB-канал на одно приложение: пока идёт трансляция, другие экраны (список, обновление, проверка
 * открытых приложений) не должны подключаться к телефону, иначе они отбирают интерфейс и всё зависает.
 * Всякий, кто открывает ADB-соединение, сначала берёт этот замок и отпускает его после закрытия соединения.
 */
object UsbLock {
    private val sem = Semaphore(1)

    fun acquire(timeoutMs: Long): Boolean = sem.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)

    fun release() = sem.release()
}
