package ru.quipy.payments.logic

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Service
import ru.quipy.payments.config.ServiceSet
import java.util.*

@Service
class PaymentServiceBalancer(
    serviceSets: List<ServiceSet>
    ) : PaymentExternalService, DisposableBean {

    private val sortedSets = serviceSets.sortedBy { it.service.cost }.toList()
    private val logger = LoggerFactory.getLogger(PaymentServiceBalancer::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun submitPaymentRequest(paymentId: UUID, amount: Int, paymentStartedAt: Long) {
        scope.launch {
            val decision = makeDecision(paymentStartedAt)
            decision.context.acquireWindow()
            decision.rateLimiter.tickBlocking()
            decision.service.submitPaymentRequest(paymentId, amount, paymentStartedAt, decision.context)
        }
    }

    private suspend fun makeDecision(paymentStartedAt: Long): ServiceSet {
        sortedSets.forEach {
            while (true) {
                val timePassed = now() - paymentStartedAt
                val timeLeft = PaymentOperationTimeout.toMillis() - timePassed
                val canWait = (timeLeft - it.service.requestAverageProcessingTime.toMillis()) / 1000.0 * it.service.speed
                val queued = it.context.jobCount.get()
                if (canWait - queued >= 1) {
                    if (it.context.jobCount.compareAndSet(queued, queued + 1))
                        return it
                } else {
                    break
                }
            }
        }

        return sortedSets.last()
    }

    override fun destroy() {
        logger.warn("Closing PaymentServiceBalancer")
        scope.cancel()
    }
}