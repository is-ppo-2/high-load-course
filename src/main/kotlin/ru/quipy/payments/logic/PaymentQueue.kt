package ru.quipy.payments.logic

import CircuitBreakerOpenException
import org.slf4j.LoggerFactory
import ru.quipy.common.utils.NamedThreadFactory
import ru.quipy.payments.config.ServiceSet
import java.util.concurrent.Executors

class PaymentQueue(
    private val set: ServiceSet,
) {
    val accountName = set.service.accountName
    private val queueExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), NamedThreadFactory("payment-queue-$accountName"))
    private val logger = LoggerFactory.getLogger(PaymentQueue::class.java)
    var fallback : (request: PaymentRequest) -> Unit = {}

    fun tryEnqueue(request: PaymentRequest): Boolean {
        while (true) {
            if (!set.circuitBreaker.canMakeCall()) return false
            val timePassed = now() - request.paymentStartedAt
            val timeLeft = PaymentOperationTimeout.toMillis() - timePassed
            val canWait = (timeLeft - set.service.requestAverageProcessingTime.toMillis()) / 1000.0 * set.service.speed
            val queued = set.window.jobCount.get()
            if (canWait - queued >= 1) {
                if (set.window.jobCount.compareAndSet(queued, queued + 1)) {
                    queueExecutor.submit{ queueJob(request) }
                    return true
                }
            } else {
                return false
            }
        }
    }

    private fun queueJob(request: PaymentRequest)
    {
        try {
            set.circuitBreaker.submitExecution()
            set.window.acquireWindow()
            set.rateLimiter.tickBlocking()
            set.service.paymentRequest(request.paymentId, request.amount, request.paymentStartedAt, set.window, set.circuitBreaker)
        }
        catch (e: CircuitBreakerOpenException) {
            logger.error("Fallback for account $accountName")
            fallback(request)
        }
        catch (e: Exception) {
            logger.error("Error while making payment on account $accountName: ${e.message}")
            set.window.release()
        }
    }

    fun destroy() {
        queueExecutor.shutdown()
        set.service.destroy()
    }
}