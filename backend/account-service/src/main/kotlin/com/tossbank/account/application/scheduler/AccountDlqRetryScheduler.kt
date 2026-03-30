package com.tossbank.account.application.scheduler

import com.tossbank.account.infrastructure.kafka.AccountDlqEventRepository
import com.tossbank.account.infrastructure.kafka.AccountDlqStatus
import mu.KotlinLogging
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class AccountDlqRetryScheduler(
    private val dlqRepository: AccountDlqEventRepository,
    private val kafkaTemplate: KafkaTemplate<String, String>,
) {
    private val log = KotlinLogging.logger {}

    @Scheduled(fixedDelay = 300_000)
    fun retryDlqEvents() {
        val events = dlqRepository.findByStatusAndRetryCountLessThan(
            status   = AccountDlqStatus.PENDING,
            maxRetry = 5,
        )
        if (events.isEmpty()) return

        events.forEach { event ->
            runCatching {
                // account.withdraw.cancel 토픽으로 재발행
                // WithdrawCancelConsumer가 다시 수신 → execute() 재시도
                kafkaTemplate.send(event.topic, event.messageKey, event.payload).get()
            }.onSuccess {
                event.status = AccountDlqStatus.RESOLVED
                dlqRepository.save(event)
                log.warn { "DLQ 재발행 성공 — id=${event.id}" }

            }.onFailure { e ->
                event.retryCount++
                event.lastError = e.message

                if (event.retryCount >= 5) {
                    event.status = AccountDlqStatus.EXHAUSTED
                    log.error { "출금 취소 DLQ 소진 → 수동 처리 필요 — id=${event.id}" }
                    // TODO: Slack 알림
                }
                dlqRepository.save(event)
            }
        }
    }
}