plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	id("org.springframework.boot") version "4.0.3"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.2.21"
}

group = "com"
version = "1.0.0"
description = "toss bank clone"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {

	// WebFlux & Coroutine
	implementation("org.springframework.boot:spring-boot-starter-webflux")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
	implementation("org.jetbrains.kotlin:kotlin-reflect")

	// JPA & MySQL
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	runtimeOnly("com.mysql:mysql-connector-j")

	// Redis & Redisson (분산 락)
	implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
	implementation("org.redisson:redisson-spring-boot-starter:4.3.0")

	// Kafka
	implementation("org.springframework.boot:spring-boot-starter-kafka")

	// Kotest & MockK
	testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
	testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")

	val kotestVersion = "6.1.6"
	testImplementation("io.kotest:kotest-runner-junit5-jvm:$kotestVersion")
	testImplementation("io.kotest:kotest-assertions-core-jvm:$kotestVersion")
	testImplementation("io.kotest.extensions:kotest-extensions-spring:1.3.0")
	testImplementation("io.mockk:mockk:1.13.17")

	testImplementation("org.springframework.boot:spring-boot-starter-test") {
		// Kotest를 사용할 것이므로 기본 JUnit Vintage와 JUnit Jupiter 엔진 충돌 방지
		exclude(module = "junit")
		exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
		exclude(group = "org.mockito", module = "mockito-core") // MockK를 쓰므로 Mockito 제외
	}
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	jvmToolchain(21)
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
