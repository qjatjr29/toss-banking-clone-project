package com.tossbank.external

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication()
class ExternalBankingServiceApplication

fun main(args: Array<String>) {
    runApplication<ExternalBankingServiceApplication>(*args)
}