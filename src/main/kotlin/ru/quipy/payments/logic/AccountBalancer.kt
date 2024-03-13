package ru.quipy.payments.logic

import kotlinx.coroutines.*
import org.springframework.beans.factory.DisposableBean
import java.time.Duration
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class AccountBalancer(
    private val service1: PaymentExternalServiceImpl,
    private val service2: PaymentExternalServiceImpl
) : PaymentExternalService, DisposableBean {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val secondAccCounter = AtomicInteger(0)

    override fun submitPaymentRequest(paymentId: UUID, amount: Int, paymentStartedAt: Long) {
        scope.launch {
            val decision = decide(paymentStartedAt)
            decision.submitPaymentRequest(paymentId, amount, paymentStartedAt)
            if (decision == service2)
                secondAccCounter.decrementAndGet()
        }
    }

    private suspend fun decide(paymentStartedAt: Long): PaymentExternalServiceImpl {
        val waitStartTime = now()
        while (Duration.ofMillis(now() - waitStartTime - paymentStartedAt) <= service2.requestAverageProcessingTime) {
            val curCount = secondAccCounter.get()
            if (curCount < service2.parallelRequests) {
                if (secondAccCounter.compareAndSet(curCount, curCount + 1)) {
                    return service2
                }
            }
            delay(1000)
        }
        return service1
    }

    override fun destroy() {
        scope.cancel()
    }
}