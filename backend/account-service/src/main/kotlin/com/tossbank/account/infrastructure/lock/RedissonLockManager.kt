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
     * 데드락 방지를 위해 계좌 ID를 오름차순 정렬하여 Redisson MultiLock을 획득
     * 항상 작은 id의 계좌를 먼저 락 → 어떤 요청 순서든 동일한 락 순서 보장
     *
     *  요청A: 계좌#1 → #2 이체 → 락 순서: #1 먼저
     *  요청B: 계좌#2 → #1 이체 → 락 순서: #1 먼저 (강제)
     *  → 원형 대기(circular wait) 불가 → 데드락 없음
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
}