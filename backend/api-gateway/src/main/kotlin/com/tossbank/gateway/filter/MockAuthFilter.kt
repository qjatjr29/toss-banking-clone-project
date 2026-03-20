package com.tossbank.gateway.filter

import mu.KotlinLogging
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

private val log = KotlinLogging.logger {}

/**
 * Mock 인증 필터
 * 실제 JWT 대신 X-User-Id 헤더로 사용자를 식별합니다.
 * 요청에 X-User-Id 헤더가 없으면 401을 반환합니다.
 */
@Component
class MockAuthFilter : GlobalFilter, Ordered {

    // 인증 불필요 경로
    private val whiteList = setOf(
        "/actuator",
        "/actuator/health",
    )

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val path = exchange.request.uri.path

        if (whiteList.any { path.startsWith(it) }) {
            return chain.filter(exchange)
        }

        val userId = exchange.request.headers.getFirst("X-User-Id")

        if (userId.isNullOrBlank()) {
            log.warn { "인증 실패: X-User-Id 헤더 없음, path=$path" }
            exchange.response.statusCode = HttpStatus.UNAUTHORIZED
            return exchange.response.setComplete()
        }

        // X-User-Id를 다운스트림 서비스로 전달 (변조 방지: 외부에서 온 헤더를 덮어씀)
        val mutatedRequest = exchange.request.mutate()
            .header("X-User-Id", userId)
            .build()

        log.debug { "인증 통과: userId=$userId, path=$path" }
        return chain.filter(exchange.mutate().request(mutatedRequest).build())
    }

    override fun getOrder(): Int = -1 // 가장 먼저 실행
}