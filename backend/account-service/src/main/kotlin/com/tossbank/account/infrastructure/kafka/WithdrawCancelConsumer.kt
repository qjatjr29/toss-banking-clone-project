package com.tossbank.account.infrastructure.kafka

import com.tossbank.account.application.WithdrawCancelExecutor
import com.tossbank.account.infrastructure.kafka.dto.WithdrawCancelMessage
import com.tossbank.account.infrastructure.lock.RedissonLockManager
import mu.KotlinLogging
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

private val log = KotlinLogging.logger {}

@Component
class WithdrawCancelConsumer(
    private val lockManager: RedissonLockManager,
    private val withdrawCancelExecutor: WithdrawCancelExecutor,
    private val dlqRepository: AccountDlqEventRepository,
    private val objectMapper: ObjectMapper,
) {

    @KafkaListener(
        topics = ["account.withdraw.cancel"],
        groupId = "account-service",
        containerFactory = "kafkaListenerContainerFactory",
    )
    fun handleWithdrawCancel(
        record: ConsumerRecord<String, String>,
        ack: Acknowledgment,
    ) {
        runCatching {

            val message = objectMapper.readValue(record.value(), WithdrawCancelMessage::class.java)
            lockManager.withSingleLock(message.fromAccountId) {
                withdrawCancelExecutor.execute(message)
            }
        }.onSuccess {
            log.warn { "출금 취소 완료" }
            ack.acknowledge()
        }.onFailure { e ->
            // 비즈니스 처리 실패 → DLQ 저장 후 ack (재처리는 DlqRetryScheduler에서 처리)
            log.error(e) { "출금 취소 실패 → DLQ 저장" }
            dlqRepository.save(
                AccountDlqEvent(
                    topic      = record.topic(),
                    payload    = record.value(),
                    messageKey = record.key(),
                    lastError  = e.message,
                )
            )
            ack.acknowledge()
        }
    }
}