package ru.quipy.payments.logic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.springframework.beans.factory.DisposableBean
import java.util.*

class AccountBalancer(
    private val services: List<PaymentExternalServiceImpl>
) : PaymentExternalService, DisposableBean {
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun submitPaymentRequest(paymentId: UUID, amount: Int, paymentStartedAt: Long) {
        val decision = decide(paymentStartedAt)
        scope.launch {
            services[decision].submitPaymentRequest(paymentId, amount, paymentStartedAt)
        }
    }

    private fun decide(paymentStartedAt: Long): Int {
        val timeLeft = PaymentOperationTimeout.toMillis() - (now() - paymentStartedAt)
        val service2Time = services[1].requestAverageProcessingTime.toMillis()

        if (timeLeft > service2Time) {
            return 1
        }

        return 0
    }

    override fun destroy() {
        scope.cancel()
    }
}