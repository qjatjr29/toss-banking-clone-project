package com.tossbank.account.infrastructure.lock

import LockAcquisitionException
import mu.KotlinLogging
import org.redisson.api.RedissonClient
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

@Component
class RedissonLockManager(
    private val redissonClient: RedissonClient
) {

    /**
     * 당행 이체 — 두 계좌 MultiLock
     * 데드락 방지: ID 오름차순 정렬 후 락 획득
     */
    fun <T> withTransferLocks(
        accountId1: Long,
        accountId2: Long,
        waitSeconds: Long = 3L,
        leaseSeconds: Long = 10L,
        block: () -> T,
    ): T {

        val (firstId, secondId) =
            if (accountId1 < accountId2) accountId1 to accountId2
            else accountId2 to accountId1

        val firstLock  = redissonClient.getLock("account:lock:$firstId")
        val secondLock = redissonClient.getLock("account:lock:$secondId")
        val multiLock = redissonClient.getMultiLock(firstLock, secondLock)

        if (!multiLock.tryLock(waitSeconds, leaseSeconds, TimeUnit.SECONDS)) {
            log.warn { "MultiLock 획득 실패: accountIds=$firstId, $secondId" }
            throw LockAcquisitionException()
        }

        return try {
            block()
        } finally {
            multiLock.unlock()
        }
    }

    // 단일 계좌 락
    fun <T> withSingleLock(
        accountId: Long,
        waitSeconds: Long = 3L,
        leaseSeconds: Long = 10L,
        block: () -> T,
    ): T {
        val lock = redissonClient.getLock("account:lock:$accountId")

        if (!lock.tryLock(waitSeconds, leaseSeconds, TimeUnit.SECONDS)) {
            log.warn { "SingleLock 획득 실패: accountId=$accountId" }
            throw LockAcquisitionException()
        }

        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}