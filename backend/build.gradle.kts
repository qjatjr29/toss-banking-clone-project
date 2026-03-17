plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	id("org.springframework.boot") version "4.0.3"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.2.21"

	// JPA 지연 로딩을 위해 엔티티 클래스를 강제로 open 상태로 만들어주는 플러그인
//	kotlin("plugin.allopen") version "2.2.21"
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

	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-redis-reactive-test")
	testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
	testImplementation("io.kotest:kotest-runner-junit5:5.8.0")
	// Kotest 검증(Assertion) 라이브러리 (shouldBe 등)
	testImplementation("io.kotest:kotest-assertions-core:5.8.0")
	// Kotest에서 @SpringBootTest 등 스프링 빈을 주입받기 위한 확장 모듈
	testImplementation("io.kotest.extensions:kotest-extensions-spring:1.1.3")
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
