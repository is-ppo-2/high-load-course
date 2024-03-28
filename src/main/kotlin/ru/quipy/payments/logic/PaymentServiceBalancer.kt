package ru.quipy.payments.logic

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Service
import ru.quipy.payments.config.ServiceSet
import ru.quipy.payments.exceptions.OutOfProcessingSpeedException
import java.util.*

@Service
class PaymentServiceBalancer(
    serviceSets: List<ServiceSet>
    ) : PaymentExternalService, DisposableBean {

    private val queues = serviceSets.sortedBy { it.service.cost }.map{ x -> PaymentQueue(x) }.toTypedArray()
    private val logger = LoggerFactory.getLogger(PaymentServiceBalancer::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun submitPaymentRequest(paymentId: UUID, amount: Int, paymentStartedAt: Long) {
        val request = PaymentRequest(paymentId, amount, paymentStartedAt)
        queues.forEach {
            if (it.tryEnqueue(request)) {
                logger.warn("$paymentId can be placed in ${it.accountName}")
                return
            }
        }

        throw OutOfProcessingSpeedException()
    }

    override fun destroy() {
        logger.warn("Closing PaymentServiceBalancer")
        scope.cancel()
    }
}