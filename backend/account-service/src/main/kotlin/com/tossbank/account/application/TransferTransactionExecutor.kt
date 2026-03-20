package com.tossbank.account.application

import AccountNotFoundException
import com.tossbank.account.domain.model.TransactionHistory
import com.tossbank.account.infrastructure.persistence.AccountRepository
import com.tossbank.account.infrastructure.persistence.TransactionHistoryRepository
import com.tossbank.account.presentation.dto.TransferRequest
import com.tossbank.account.presentation.dto.TransferResponse
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

private val log = KotlinLogging.logger {}

@Component
class TransferTransactionExecutor(
    private val accountRepository: AccountRepository,
    private val transactionHistoryRepository: TransactionHistoryRepository,
) {

    @Transactional
    fun execute(
        memberId: Long,
        request: TransferRequest,
        toAccountId: Long,
        fromMemberName: String,
    ): TransferResponse {

        // 멱등성 체크
        transactionHistoryRepository.findByIdempotencyKey(request.idempotencyKey)
            ?.let { existing ->
                log.warn { "중복 이체 감지 — 기존 결과 반환: key=${request.idempotencyKey}" }
                return TransferResponse(
                    fromAccountId    = existing.accountId,
                    toMemberName     = request.toMemberName,
                    amount           = existing.amount,
                    remainingBalance = existing.balanceAfterTx,
                )
            }

        // 비관적 락
        // 항상 작은 ID 순서로 FOR UPDATE — 데드락 방지, Redisson 락 순서와 동일한 기준으로 정렬
        val (firstId, secondId) =
            if (request.fromAccountId < toAccountId)
                request.fromAccountId to toAccountId
            else
                toAccountId to request.fromAccountId

        val firstAccount = accountRepository.findByIdWithLock(firstId)
            ?: run {
                // 사전 검증 통과 후 없으면 → 데이터 정합성 이상 (삭제 불가 정책이므로 발생 불가)
                log.error { "락 획득 후 계좌 조회 실패 — 데이터 정합성 이상: accountId=$firstId" }
                throw AccountNotFoundException()
            }

        val secondAccount = accountRepository.findByIdWithLock(secondId)
            ?: run {
                log.error { "락 획득 후 계좌 조회 실패 — 데이터 정합성 이상: accountId=$secondId" }
                throw AccountNotFoundException()
            }

        // ID 정렬로 뒤바뀐 from/to 방향 복원
        val fromAccount = if (request.fromAccountId == firstId) firstAccount else secondAccount
        val toAccount   = if (request.fromAccountId == firstId) secondAccount else firstAccount

        fromAccount.verifyOwner(memberId)
        fromAccount.withdraw(request.amount)
        toAccount.deposit(request.amount)

        // 거래 내역 저장 — 이체 시점 이름 스냅샷 기록
        transactionHistoryRepository.saveAll(
            listOf(
                TransactionHistory.ofTransfer(
                    accountId                = fromAccount.id,
                    amount                   = request.amount,
                    balanceAfterTx           = fromAccount.balance,
                    counterpartAccountNumber = toAccount.accountNumber,
                    counterpartName          = request.toMemberName,   // 계좌실명조회 결과
                    description              = request.description ?: "${request.toMemberName}에게 이체",
                    isOutgoing               = true,
                    idempotencyKey           = request.idempotencyKey,
                ),
                TransactionHistory.ofTransfer(
                    accountId                = toAccount.id,
                    amount                   = request.amount,
                    balanceAfterTx           = toAccount.balance,
                    counterpartAccountNumber = fromAccount.accountNumber,
                    counterpartName          = fromMemberName,
                    description              = request.description ?: "${fromMemberName}으로부터 입금",
                    isOutgoing               = false,
                    idempotencyKey           = null,
                ),
            )
        )

        log.info {
            "이체 완료: from=${fromAccount.id}(잔액:${fromAccount.balance}) " +
                    "to=${toAccount.id}(잔액:${toAccount.balance}) amount=${request.amount}"
        }

        return TransferResponse(
            fromAccountId    = fromAccount.id,
            toMemberName     = request.toMemberName,
            amount           = request.amount,
            remainingBalance = fromAccount.balance,
        )
    }
}