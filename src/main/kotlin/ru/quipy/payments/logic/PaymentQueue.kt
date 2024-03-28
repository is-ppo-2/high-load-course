package ru.quipy.payments.logic

import kotlinx.coroutines.*
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.payments.config.ServiceSet
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

class PaymentQueue(
    private val set: ServiceSet,
) {
    val accountName = set.service.accountName
    private val queueContext = CoroutineScope(SupervisorJob() +
            Executors.newFixedThreadPool(4, NamedThreadFactory("payment-queue-$accountName"))
        .asCoroutineDispatcher())

    private val queue = ConcurrentLinkedQueue<PaymentRequest>()

    fun tryEnqueue(request: PaymentRequest): Boolean {
        while (true) {
            val timePassed = now() - request.paymentStartedAt
            val timeLeft = PaymentOperationTimeout.toMillis() - timePassed
            val canWait = (timeLeft - set.service.requestAverageProcessingTime.toMillis()) / 1000.0 * set.service.speed
            val queued = set.window.jobCount.get()
            if (canWait - queued >= 1) {
                if (set.window.jobCount.compareAndSet(queued, queued + 1)) {
                    queue.add(request)
                    return true
                }
            } else {
                return false
            }
        }
    }

    private val queueJob = queueContext.launch {
        while (true) {
            val task = queue.poll()
            if (task != null) {
                set.window.acquireWindow()
                set.rateLimiter.tickBlocking()
                set.service.submitPaymentRequest(task.paymentId, task.amount, task.paymentStartedAt, set.window)
            }
            else yield()
        }
    }

    fun destroy() {
        queueContext.cancel()
        set.service.destroy()
    }
}