package com.tossbank.account.application

import AccountNotFoundException
import com.tossbank.account.domain.model.TransactionHistory
import com.tossbank.account.infrastructure.kafka.dto.WithdrawCancelMessage
import com.tossbank.account.infrastructure.persistence.AccountRepository
import com.tossbank.account.infrastructure.persistence.TransactionHistoryRepository
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

private val log = KotlinLogging.logger {}

@Component
class WithdrawCancelExecutor(
    private val accountRepository: AccountRepository,
    private val transactionHistoryRepository: TransactionHistoryRepository,
) {

    @Transactional
    fun execute(message: WithdrawCancelMessage) {
        // 멱등성 체크
        val compensateKey = "${message.idempotencyKey}-cancel"

        if (transactionHistoryRepository.existsByIdempotencyKey(compensateKey)) {
            log.warn { "중복 보상 요청 스킵 — key=$compensateKey" }
            return
        }
        val account = accountRepository.findByIdWithLock(message.fromAccountId)
            ?: throw AccountNotFoundException()

        account.deposit(message.amount)
        transactionHistoryRepository.save(
            TransactionHistory.ofInternalWithdrawCancel(
                accountId      = message.fromAccountId,
                amount         = message.amount,
                balanceAfterTx = account.balance,
                idempotencyKey = compensateKey,
            )
        )

        log.warn { "출금 취소 처리 완료 — accountId=${message.fromAccountId}" }
    }
}