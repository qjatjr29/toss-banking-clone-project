package com.tossbank.account.application

import AccountNotFoundException
import com.tossbank.account.domain.model.TransactionHistory
import com.tossbank.account.infrastructure.persistence.AccountRepository
import com.tossbank.account.infrastructure.persistence.TransactionHistoryRepository
import com.tossbank.account.presentation.dto.*
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

private val log = KotlinLogging.logger {}

@Component
class InternalTransferExecutor(
    private val accountRepository: AccountRepository,
    private val transactionHistoryRepository: TransactionHistoryRepository,
) {

    @Transactional
    fun executeWithdraw(request: InternalWithdrawRequest): InternalWithdrawResponse {

        // 멱등성 체크 — 이미 처리된 경우 기존 결과 반환
        transactionHistoryRepository.findByIdempotencyKey(request.idempotencyKey)
            ?.let { existing ->
                log.warn { "중복 출금 요청 — 기존 결과 반환: key=${request.idempotencyKey}" }
                return InternalWithdrawResponse(
                    fromAccountId    = existing.accountId,
                    remainingBalance = existing.balanceAfterTx,
                )
            }

        val fromAccount = accountRepository.findByIdWithLock(request.fromAccountId)
            ?: throw AccountNotFoundException()

        fromAccount.verifyOwner(request.fromMemberId)
        fromAccount.withdraw(request.amount)

        transactionHistoryRepository.save(
            TransactionHistory.ofInternalWithdraw(
                accountId      = fromAccount.id,
                amount         = request.amount,
                balanceAfterTx = fromAccount.balance,
                idempotencyKey = request.idempotencyKey,
            )
        )

        return InternalWithdrawResponse(
            fromAccountId    = fromAccount.id,
            remainingBalance = fromAccount.balance,
        )
    }

    @Transactional
    fun executeDeposit(request: InternalDepositRequest): InternalDepositResponse {

        transactionHistoryRepository.findByIdempotencyKey(request.idempotencyKey)
            ?.let { existing ->
                log.warn { "중복 입금 요청 — 기존 결과 반환: key=${request.idempotencyKey}" }
                return InternalDepositResponse(
                    toAccountId    = existing.accountId,
                    balanceAfterTx = existing.balanceAfterTx,
                )
            }

        val toAccount = accountRepository.findByIdWithLock(request.toAccountId)
            ?: throw AccountNotFoundException()

        toAccount.deposit(request.amount)

        transactionHistoryRepository.save(
            TransactionHistory.ofInternalDeposit(
                accountId      = toAccount.id,
                amount         = request.amount,
                balanceAfterTx = toAccount.balance,
                fromMemberName = request.fromMemberName,
                description    = request.description,
                idempotencyKey = request.idempotencyKey,
            )
        )

        return InternalDepositResponse(
            toAccountId    = toAccount.id,
            balanceAfterTx = toAccount.balance,
        )
    }

    @Transactional(readOnly = true)
    fun inquireTransaction(idempotencyKey: String): TransactionInquiryResponse {
        val history = transactionHistoryRepository.findByIdempotencyKey(idempotencyKey)
            ?: return TransactionInquiryResponse(status = InquiryStatus.NOT_FOUND)

        return TransactionInquiryResponse(
            status  = InquiryStatus.SUCCESS,
            balance = history.balanceAfterTx,
        )
    }
}