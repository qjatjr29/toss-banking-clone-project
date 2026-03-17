package com.tossbank

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TossBankApplication

fun main(args: Array<String>) {
	runApplication<TossBankApplication>(*args)
}
