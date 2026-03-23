package com.tossbank.account.infrastructure.config

import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.util.concurrent.TimeUnit

private val log = KotlinLogging.logger {}

@Configuration
class WebClientConfig(
    @Value("\${external-bank.base-url}")       private val baseUrl: String,
    @Value("\${external-bank.connect-timeout-ms:3000}") private val connectTimeoutMs: Int,
    @Value("\${external-bank.read-timeout-ms:5000}")    private val readTimeoutMs: Long,
) {

    @Bean("externalBankWebClient")
    fun externalBankWebClient(): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
            .doOnConnected { conn ->
                conn.addHandlerLast(ReadTimeoutHandler(readTimeoutMs, TimeUnit.MILLISECONDS))
                conn.addHandlerLast(WriteTimeoutHandler(readTimeoutMs, TimeUnit.MILLISECONDS))
            }

        return WebClient.builder()
            .baseUrl(baseUrl)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .filter(loggingFilter())
            .build()
    }

    // 요청/응답 로깅 필터
    private fun loggingFilter(): ExchangeFilterFunction =
        ExchangeFilterFunction { request, next ->
            log.debug {
                "외부 은행 API 요청: ${request.method()} ${request.url()}"
            }
            next.exchange(request).doOnNext { response ->
                log.debug {
                    "외부 은행 API 응답: ${response.statusCode()}"
                }
            }
        }
}