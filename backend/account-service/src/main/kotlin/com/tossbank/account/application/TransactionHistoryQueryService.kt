package com.tossbank.account.application

import AccountNotFoundException
import com.tossbank.account.domain.model.TransactionHistory
import com.tossbank.account.infrastructure.persistence.AccountRepository
import com.tossbank.account.infrastructure.persistence.TransactionHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Slice
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class TransactionHistoryQueryService(
    private val accountRepository: AccountRepository,
    private val transactionHistoryRepository: TransactionHistoryRepository
) {
    suspend fun getHistory(accountId: Long, page: Int, size: Int): Slice<TransactionHistory> =
        withContext(Dispatchers.IO) {
            accountRepository.findById(accountId).orElseThrow { AccountNotFoundException() }
            val pageable = PageRequest.of(page, size)
            transactionHistoryRepository.findByAccountIdOrderByCreatedAtDesc(accountId, pageable)
        }
}