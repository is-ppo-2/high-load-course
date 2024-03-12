package ru.quipy.payments.logic

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Service
import ru.quipy.common.utils.CoroutineOngoingWindow
import ru.quipy.common.utils.CoroutineRateLimiter
import java.util.*

@Service
class PaymentServiceBalancer(
    private val services: List<PaymentExternalServiceImpl>,
    private val rateLimiters: List<CoroutineRateLimiter>,
    private val windows: List<CoroutineOngoingWindow>
    ) : PaymentExternalService, DisposableBean {

    private val logger = LoggerFactory.getLogger(PaymentServiceBalancer::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    override fun submitPaymentRequest(paymentId: UUID, amount: Int, paymentStartedAt: Long) {
        val timePassed = now() - paymentStartedAt
        val timeLeft = PaymentOperationTimeout.toMillis() - timePassed

        scope.launch {
            mutex.withLock {
                val decision = makeDecision()
                val rateLimiter = rateLimiters[decision]
                val window =  windows[decision]
                rateLimiter.tickBlocking()
                window.acquire()
                logger.warn("Dec: $decision, RL: ${rateLimiter.permits}, OW: ${window.permits} /")
                services[decision].submitPaymentRequest(paymentId, amount, paymentStartedAt, windows[decision])
            }
        }
    }

    private fun makeDecision(): Int {
        if (rateLimiters[1].permits == 0)
            return 0
        if (windows[1].permits == 0)
            return 0
        return 1
    }

    override fun destroy() {
        logger.warn("Closing PaymentServiceBalancer")
        scope.cancel()
    }
}