package com.tossbank.external.infrastructure.persistence

import com.tossbank.external.domain.model.InboundTransfer
import org.springframework.data.jpa.repository.JpaRepository

interface InboundTransferRepository : JpaRepository<InboundTransfer, Long> {
    fun findByIdempotencyKey(idempotencyKey: String): InboundTransfer?
}