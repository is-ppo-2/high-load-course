package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.*
import org.slf4j.LoggerFactory
import ru.quipy.common.utils.TaskContext
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.*
import java.util.concurrent.Executors


class PaymentExternalServiceImpl(
    private val properties: ExternalServiceProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>
) {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalServiceImpl::class.java)

        val emptyBody = RequestBody.create(null, ByteArray(0))
        val mapper = ObjectMapper().registerKotlinModule()
    }

    val serviceName = properties.serviceName
    val accountName = properties.accountName
    val requestAverageProcessingTime = properties.request95thPercentileProcessingTime
    val speed = properties.speed
    val cost = properties.cost

    private val httpClientExecutor = Executors.newFixedThreadPool(4)

    private val client = OkHttpClient.Builder() .run {
        dispatcher(Dispatcher(httpClientExecutor).apply {
            maxRequests = properties.parallelRequests
            maxRequestsPerHost = properties.parallelRequests
        })
        connectTimeout(requestAverageProcessingTime)
        readTimeout(requestAverageProcessingTime)
        retryOnConnectionFailure(false)
        build()
    }

    fun submitPaymentRequest(paymentId: UUID, amount: Int, paymentStartedAt: Long, context: TaskContext) {
        val passed = now() - paymentStartedAt
        logger.warn("[$accountName] Submitting payment request for payment $paymentId. Already passed: $passed ms")

        val transactionId = UUID.randomUUID()

        if (Duration.ofMillis(passed) > PaymentOperationTimeout) {
            paymentESService.update(paymentId) {
                it.logProcessing(false, now(), transactionId, reason = "Request timeout.")
            }
            return
        }
        logger.info("[$accountName] Submit for $paymentId , txId: $transactionId")

        // Вне зависимости от исхода оплаты важно отметить что она была отправлена.
        // Это требуется сделать ВО ВСЕХ СЛУЧАЯХ, поскольку эта информация используется сервисом тестирования.
        paymentESService.update(paymentId) {
            it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
        }

        val request = Request.Builder().run {
            url("http://localhost:1234/external/process?serviceName=${serviceName}&accountName=${accountName}&transactionId=$transactionId")
            post(emptyBody)
        }.build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                context.release()
                handleException(paymentId, transactionId, e)
            }

            override fun onResponse(call: Call, response: Response) {
                context.release()
                val body = try {
                    mapper.readValue(response.body?.string(), ExternalSysResponse::class.java)
                } catch (e: Exception) {
                    logger.error("[$accountName] [ERROR] Payment processed for txId: $transactionId, payment: $paymentId, result code: ${response.code}, reason: ${response.body?.string()}")
                    ExternalSysResponse(false, e.message)
                }

                logger.warn("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${body.result}, message: ${body.message}")

                // Здесь мы обновляем состояние оплаты в зависимости от результата в базе данных оплат.
                // Это требуется сделать ВО ВСЕХ ИСХОДАХ (успешная оплата / неуспешная / ошибочная ситуация)
                paymentESService.update(paymentId) {
                    it.logProcessing(body.result, now(), transactionId, reason = body.message)
                }
            }
        })
    }

    private fun handleException(paymentId: UUID, transactionId: UUID, exception: Exception) {
        when (exception) {
            is SocketTimeoutException -> {
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, reason = "Request timeout.")
                }
            }

            else -> {
                logger.error(
                    "[$accountName] Payment failed for txId: $transactionId, payment: $paymentId",
                    exception
                )

                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, reason = exception.message)
                }
            }
        }
    }
}

fun now() = System.currentTimeMillis()