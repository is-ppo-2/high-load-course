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
        val decision = decide()
        scope.launch {
            services[decision].submitPaymentRequest(paymentId, amount, paymentStartedAt)
        }
    }

    private fun decide(): Int {
        // TODO:  
    }

    override fun destroy() {
        scope.cancel()
    }
}