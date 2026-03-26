package com.tossbank.account

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class AccountServiceApplication

fun main(args: Array<String>) {
    runApplication<AccountServiceApplication>(*args)
}