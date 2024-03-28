package ru.quipy.payments.logic

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import ru.quipy.payments.config.ServiceSet
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

class PaymentQueue(
    private val set: ServiceSet,
) {
    private val queueContext = CoroutineScope(SupervisorJob() + Executors.newFixedThreadPool(4).asCoroutineDispatcher())
    private val queue = ConcurrentLinkedQueue<PaymentRequest>()
    val accountName = set.service.accountName

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
            yield()
        }
    }
}