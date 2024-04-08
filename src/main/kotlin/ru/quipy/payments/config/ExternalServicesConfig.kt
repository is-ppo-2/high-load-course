package ru.quipy.payments.config

import MyCircuitBreaker
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.quipy.common.utils.NonBlockingOngoingWindow
import ru.quipy.common.utils.RateLimiter
import ru.quipy.common.utils.TaskWindow
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.payments.logic.ExternalServiceProperties
import ru.quipy.payments.logic.PaymentAggregateState
import ru.quipy.payments.logic.PaymentExternalServiceImpl
import ru.quipy.payments.logic.PaymentServiceBalancer
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit


@Configuration
class ExternalServicesConfig(
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>
) {
    companion object {
        const val PRIMARY_PAYMENT_BEAN = "PRIMARY_PAYMENT_BEAN"

        // Ниже приведены готовые конфигурации нескольких аккаунтов провайдера оплаты.
        // Заметьте, что каждый аккаунт обладает своими характеристиками и стоимостью вызова.

        private val accountProps_1 = ExternalServiceProperties(
            // most expensive. Call costs 100
            "test",
            "default-1",
            parallelRequests = 10000,
            rateLimitPerSec = 100,
            request95thPercentileProcessingTime = Duration.ofMillis(1000),
            cost = 100
        )

        private val accountProps_2 = ExternalServiceProperties(
            // Call costs 70
            "test",
            "default-2",
            parallelRequests = 100,
            rateLimitPerSec = 30,
            request95thPercentileProcessingTime = Duration.ofMillis(5_000),
            cost = 70
        )

        private val accountProps_3 = ExternalServiceProperties(
            // Call costs 40
            "test",
            "default-3",
            parallelRequests = 30,
            rateLimitPerSec = 8,
            request95thPercentileProcessingTime = Duration.ofMillis(10_000),
            cost = 40
        )

        // Call costs 35
        private val accountProps_4 = ExternalServiceProperties(
            "test",
            "default-42",
            parallelRequests = 8,
            rateLimitPerSec = 5,
            request95thPercentileProcessingTime = Duration.ofMillis(10_000),
            cost = 35
        )

        private val accountProps_5 = ExternalServiceProperties(
            "test",
            "default-5",
            parallelRequests = 8,
            rateLimitPerSec = 5,
            request95thPercentileProcessingTime = Duration.ofMillis(10_000),
            cost = 30
        )
    }

    private val circuitBreaker2 = MyCircuitBreaker("account2-breaker", 0.2, 50, 20_000)
    private val circuitBreaker3 = MyCircuitBreaker("account3-breaker", 0.2, 50, 20_000)
    private val circuitBreaker4 = MyCircuitBreaker("account42-breaker", 0.2, 30, 20_000)
    private val circuitBreaker5 = MyCircuitBreaker("account5-breaker", 0.2, 30, 20_000)

    @Bean(PRIMARY_PAYMENT_BEAN)
    fun optimalExternalService() =
        PaymentServiceBalancer(
            listOf(
                ServiceSet(
                    PaymentExternalServiceImpl(accountProps_2, paymentESService),
                    RateLimiter(accountProps_2.rateLimitPerSec, TimeUnit.SECONDS),
                    TaskWindow(NonBlockingOngoingWindow(accountProps_2.parallelRequests)),
                    circuitBreaker2
                ),
                ServiceSet(
                    PaymentExternalServiceImpl(accountProps_3, paymentESService),
                    RateLimiter(accountProps_3.rateLimitPerSec, TimeUnit.SECONDS),
                    TaskWindow(NonBlockingOngoingWindow(accountProps_3.parallelRequests)),
                    circuitBreaker3
                ),
                ServiceSet(
                    PaymentExternalServiceImpl(accountProps_4, paymentESService),
                    RateLimiter(accountProps_4.rateLimitPerSec, TimeUnit.SECONDS),
                    TaskWindow(NonBlockingOngoingWindow(accountProps_4.parallelRequests)),
                    circuitBreaker4
                ),
                ServiceSet(
                    PaymentExternalServiceImpl(accountProps_5, paymentESService),
                    RateLimiter(accountProps_5.rateLimitPerSec, TimeUnit.SECONDS),
                    TaskWindow(NonBlockingOngoingWindow(accountProps_5.parallelRequests)),
                    circuitBreaker5
                ),
            ),
            paymentESService
        )
}

class ServiceSet(
    val service: PaymentExternalServiceImpl,
    val rateLimiter: RateLimiter,
    val window: TaskWindow,
    val circuitBreaker: MyCircuitBreaker
)