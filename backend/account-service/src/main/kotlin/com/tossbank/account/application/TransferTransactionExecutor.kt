package com.tossbank.account.application

import AccountNotFoundException
import ExternalTransferFailedException
import InterbankTransferNotFoundException
import com.tossbank.account.application.dto.InterbankTransferContext
import com.tossbank.account.domain.model.InterbankTransfer
import com.tossbank.account.domain.model.InterbankTransferStatus
import com.tossbank.account.domain.model.TransactionHistory
import com.tossbank.account.infrastructure.persistence.AccountRepository
import com.tossbank.account.infrastructure.persistence.InterbankTransferRepository
import com.tossbank.account.infrastructure.persistence.TransactionHistoryRepository
import com.tossbank.account.presentation.dto.TransferResponse
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

private val log = KotlinLogging.logger {}

@Component
class TransferTransactionExecutor(
    private val accountRepository: AccountRepository,
    private val transactionHistoryRepository: TransactionHistoryRepository,
    private val interbankTransferRepository: InterbankTransferRepository,
) {

    @Transactional
    fun executeInterbankWithdraw(
        memberId: Long,
        fromAccountId: Long,
        toAccountNumber: String,
        toBankCode: String,
        toMemberName: String,
        amount: BigDecimal,
        description: String?,
        idempotencyKey: String,
    ): InterbankTransferContext {

        interbankTransferRepository.findByIdempotencyKey(idempotencyKey)
            ?.let { existing ->
                when (existing.status) {
                    // 성공 / 진행 중 → 기존 결과 반환 (재처리 금지)
                    InterbankTransferStatus.WITHDRAW_COMPLETED,
                    InterbankTransferStatus.COMPLETED,
                    InterbankTransferStatus.UNKNOWN -> {
                        log.warn { "중복 요청 — 기존 결과 반환: key=$idempotencyKey status=${existing.status}" }
                        val history = transactionHistoryRepository.findByIdempotencyKey(idempotencyKey)
                            ?: throw InterbankTransferNotFoundException()
                        return InterbankTransferContext(
                            transferResponse    = TransferResponse(
                                fromAccountId    = existing.fromAccountId,
                                toMemberName     = existing.toMemberName,
                                amount           = existing.amount,
                                remainingBalance = history.balanceAfterTx,
                            ),
                            interbankTransferId = existing.id,
                            fromAccountId       = existing.fromAccountId,
                            fromAccountNumber   = existing.fromAccountNumber,
                        )
                    }

                    // 보상 완료 / 확정 실패 → idempotencyKey null 처리
                    InterbankTransferStatus.COMPENSATED,
                    InterbankTransferStatus.FAILED -> {
                        log.info { "실패 건 재시도 허용: key=$idempotencyKey status=${existing.status}" }
                    }

                    // 보상 진행 중 → 완료 전 재시도 차단
                    InterbankTransferStatus.COMPENSATION_PENDING,
                    InterbankTransferStatus.MANUAL_REQUIRED -> {
                        log.warn { "보상 진행 중 재시도 차단: key=$idempotencyKey status=${existing.status}" }
                        throw ExternalTransferFailedException()
                    }

                    else -> {  }
                }
            }

        val fromAccount = accountRepository.findByIdWithLock(fromAccountId)
            ?: throw AccountNotFoundException()

        fromAccount.verifyOwner(memberId)
        fromAccount.withdraw(amount)

        transactionHistoryRepository.save(
            TransactionHistory.ofInterbankWithdraw(
                accountId       = fromAccount.id,
                amount          = amount,
                balanceAfterTx  = fromAccount.balance,
                toAccountNumber = toAccountNumber,
                toMemberName    = toMemberName,
                toBankCode      = toBankCode,
                description     = description,
                idempotencyKey  = idempotencyKey,
            )
        )

        val interbankTransfer = interbankTransferRepository.save(InterbankTransfer(
            fromMemberId      = memberId,
            fromAccountId     = fromAccount.id,
            fromAccountNumber = fromAccount.accountNumber,
            toAccountNumber   = toAccountNumber,
            toBankCode        = toBankCode,
            toMemberName      = toMemberName,
            amount            = amount,
            description       = description,
            idempotencyKey    = idempotencyKey,
        ).also { it.markWithdrawCompleted() })

        return InterbankTransferContext(
            transferResponse    = TransferResponse(
                fromAccountId    = fromAccount.id,
                toMemberName     = toMemberName,
                amount           = amount,
                remainingBalance = fromAccount.balance,
            ),
            interbankTransferId = interbankTransfer.id,
            fromAccountId       = fromAccount.id,
            fromAccountNumber   = fromAccount.accountNumber,
        )
    }

    @Transactional
    fun markInterbankCompleted(interbankTransferId: Long, externalTxId: String) {
        val transfer = findInterbankTransfer(interbankTransferId)
        transfer.markCompleted(externalTxId)
        log.info { "타행 이체 COMPLETED: id=$interbankTransferId externalTxId=$externalTxId" }
    }

    @Transactional
    fun markInterbankUnknown(interbankTransferId: Long, errorMessage: String) {
        val transfer = findInterbankTransfer(interbankTransferId)
        transfer.markUnknown(errorMessage)
        // ⚠️ 보상 트랜잭션 안함!! - 입금 여부 불확실 하기 때문.
        log.error { "타행 이체 UNKNOWN: id=$interbankTransferId error=$errorMessage" }
    }

    @Transactional
    fun markInterbankManualRequired(interbankTransferId: Long, errorMessage: String) {
        val transfer = findInterbankTransfer(interbankTransferId)
        transfer.markManualRequired(errorMessage)
        // TODO: Slack 알림
        log.error { "MANUAL_REQUIRED: id=$interbankTransferId" }
    }

    /**
     * REQUIRES_NEW — 메인 트랜잭션이 롤백되어도 독립적으로 커밋
     * 보상 실패 상태(COMPENSATION_PENDING / MANUAL_REQUIRED)를 반드시 저장해야
     * 스케줄러가 재시도할 수 있음
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markCompensationFailed(interbankTransferId: Long, errorMessage: String) {
        val transfer = findInterbankTransfer(interbankTransferId)
        if (transfer.isRetryExhausted()) {
            transfer.markManualRequired("보상 재시도 한계 초과: $errorMessage")
            // TODO: Slack 알림
            log.error { "보상 재시도 한계 초과 MANUAL_REQUIRED: id=$interbankTransferId" }
        } else {
            transfer.markCompensationPending("보상 실패: $errorMessage")
            log.error { "보상 실패 → COMPENSATION_PENDING: id=$interbankTransferId retry=${transfer.retryCount}" }
        }
    }

    /**
     * 보상 트랜잭션
     *
     * 호출 시점
     *  1. 외부 은행 API 4xx 반환시 → 이 메서드 직접 호출
     *  2. COMPENSATION_PENDING 스케줄러 재시도 (보상 트랜잭션 스케줄러)
     *
     * 보상 트랜잭션 자체도 실패할 수 있음
     *  → try/catch 로 잡아 COMPENSATION_PENDING + scheduleNextRetry
     *  → MAX_RETRY 초과 시 MANUAL_REQUIRED + 개발자 알림(TODO)
     */
    @Transactional
    fun compensateInterbank(interbankTransferId: Long) {
        val transfer = findInterbankTransfer(interbankTransferId)

        val fromAccount = accountRepository.findByIdWithLock(transfer.fromAccountId)
            ?: throw AccountNotFoundException()

        val idempotencyKey = transfer.idempotencyKey
            ?: throw IllegalStateException("idempotencyKey null: id=$interbankTransferId")

        fromAccount.deposit(transfer.amount)
        transactionHistoryRepository.findByIdempotencyKey(idempotencyKey)
            ?.releaseIdempotencyKey()

        transactionHistoryRepository.save(
            TransactionHistory.ofInterbankWithdrawCancel(
                accountId       = fromAccount.id,
                amount          = transfer.amount,
                balanceAfterTx  = fromAccount.balance,
                toAccountNumber = transfer.toAccountNumber,
                toBankCode      = transfer.toBankCode,
                idempotencyKey  = idempotencyKey,
            )
        )
        transfer.markCompensated()
        log.debug { "보상 트랜잭션 완료: id=$interbankTransferId" }
    }

    // 스케줄러에서 UNKNOWN 재조회 실패 / PROCESSING 시 nextRetryAt 갱신
    @Transactional
    fun scheduleNextRetryForUnknown(
        interbankTransferId : Long,
        errorMessage        : String = "retry scheduled",
    ) {
        val transfer = findInterbankTransfer(interbankTransferId)
        if (transfer.status != InterbankTransferStatus.UNKNOWN) {
            log.warn { "scheduleNextRetryForUnknown 스킵 — 이미 상태 변경됨: status=${transfer.status}" }
            return
        }
        transfer.lastErrorMessage = errorMessage
        transfer.scheduleNextRetry()
    }

    private fun findInterbankTransfer(id: Long): InterbankTransfer =
        interbankTransferRepository.findById(id).orElseThrow { InterbankTransferNotFoundException() }
}