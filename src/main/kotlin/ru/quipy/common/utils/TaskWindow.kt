package ru.quipy.common.utils

import java.util.concurrent.atomic.AtomicInteger

class TaskWindow(
   private val window: CoroutineOngoingWindow,
) {
    var jobCount: AtomicInteger = AtomicInteger(0)

    fun release() {
        window.release()
        jobCount.decrementAndGet()
    }

    suspend fun acquireWindow() {
        window.acquire()
    }

    fun tryAcquireWindow(): Boolean {
        return window.tryAcquire()
    }
}