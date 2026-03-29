package com.tossbank.account.application

import com.tossbank.account.infrastructure.lock.RedissonLockManager
import com.tossbank.account.presentation.dto.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

@Service
class InternalTransferService(
    private val lockManager: RedissonLockManager,
    private val executor: InternalTransferExecutor,
    @Qualifier("dbDispatcher") private val dbDispatcher: CoroutineDispatcher,
) {

    suspend fun withdraw(request: InternalWithdrawRequest): InternalWithdrawResponse =
        withContext(dbDispatcher) {
            lockManager.withSingleLock(request.fromAccountId) {
                executor.executeWithdraw(request)
            }
        }

    suspend fun deposit(request: InternalDepositRequest): InternalDepositResponse =
        withContext(dbDispatcher) {
            lockManager.withSingleLock(request.toAccountId) {
                executor.executeDeposit(request)
            }
        }

    suspend fun inquireTransaction(idempotencyKey: String): TransactionInquiryResponse =
        withContext(dbDispatcher) {
            executor.inquireTransaction(idempotencyKey)
        }
}
