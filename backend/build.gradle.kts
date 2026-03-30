import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
	kotlin("jvm") version "2.2.21" apply false
	kotlin("plugin.spring") version "2.2.21" apply false
	id("org.springframework.boot") version "4.0.3" apply false
	id("io.spring.dependency-management") version "1.1.7" apply false
	kotlin("plugin.jpa") version "2.2.21" apply false

}

val javaVersion = JavaVersion.VERSION_21
val springCloudVersion = "2025.1.0"

allprojects {
	group = "com.tossbank"
	version = "1.0.0"
	repositories { mavenCentral() }
}

subprojects {
	apply(plugin = "org.jetbrains.kotlin.jvm")
	apply(plugin = "org.jetbrains.kotlin.plugin.spring")
	apply(plugin = "org.jetbrains.kotlin.plugin.jpa")
	apply(plugin = "org.springframework.boot")
	apply(plugin = "io.spring.dependency-management")

	configure<JavaPluginExtension> {
		sourceCompatibility = javaVersion
		toolchain {  languageVersion.set(JavaLanguageVersion.of(21)) }
	}

	dependencies {
		val implementation by configurations
		val testImplementation by configurations

		implementation(platform("org.springframework.cloud:spring-cloud-dependencies:$springCloudVersion"))

		// Kotlin & Coroutines
		implementation("org.jetbrains.kotlin:kotlin-reflect")
		implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
		implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
		implementation("tools.jackson.module:jackson-module-kotlin")

		// Logging
		implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

		// Test
		testImplementation("org.springframework.boot:spring-boot-starter-test")
		testImplementation("io.projectreactor:reactor-test")
		testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
	}

	tasks.withType<KotlinCompile> {
		compilerOptions {
			freeCompilerArgs.addAll("-Xjsr305=strict")
			jvmTarget.set(JvmTarget.JVM_21)
		}
	}

	tasks.withType<Test> {
		useJUnitPlatform()
	}

	tasks.withType<org.springframework.boot.gradle.tasks.bundling.BootBuildImage> {
		imageName.set("tossbank/${project.name}:${project.version}")
//		builder.set("paketobuildpacks/builder:base")
	}
}