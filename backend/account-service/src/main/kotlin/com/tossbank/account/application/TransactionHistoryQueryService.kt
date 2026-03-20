package com.tossbank.account.application

import AccountNotFoundException
import com.tossbank.account.domain.model.TransactionHistory
import com.tossbank.account.infrastructure.persistence.AccountRepository
import com.tossbank.account.infrastructure.persistence.TransactionHistoryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Slice
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class TransactionHistoryQueryService(
    private val accountRepository: AccountRepository,
    private val transactionHistoryRepository: TransactionHistoryRepository,
    @Qualifier("dbDispatcher") private val dbDispatcher: CoroutineDispatcher,
) {

    // TODO: cursor 방식
    suspend fun getHistory(memberId: Long, accountId: Long, page: Int, size: Int): Slice<TransactionHistory> =
        withContext(dbDispatcher) {
            val account = accountRepository.findById(accountId)
                .orElseThrow { AccountNotFoundException() }
            account.verifyOwner(memberId)

            val pageable = PageRequest.of(page, size)
            transactionHistoryRepository.findByAccountIdOrderByCreatedAtDesc(accountId, pageable)
        }

}