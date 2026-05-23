import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.serialization") version "2.3.21"
	id("org.jetbrains.compose") version "1.11.0"
	id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
}

group = "com.wzx.babiq.desktop"
version = "0.0.1-SNAPSHOT"

repositories {
	mavenCentral()
	google()
	maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
	val ktorVersion = "3.5.0"
	val kotlinxSerializationVersion = "1.11.0"
	val kotlinxCoroutinesVersion = "1.11.0"

	implementation(compose.desktop.currentOs)
	implementation(compose.material3)
	implementation("io.ktor:ktor-client-core:$ktorVersion")
	implementation("io.ktor:ktor-client-cio:$ktorVersion")
	implementation("io.ktor:ktor-client-websockets:$ktorVersion")
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:$kotlinxCoroutinesVersion")

	testImplementation(kotlin("test"))
	testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$kotlinxCoroutinesVersion")
}

kotlin {
	jvmToolchain(21)
}

compose.desktop {
	application {
		mainClass = "com.wzx.babiq.desktop.MainKt"
		nativeDistributions {
			targetFormats(TargetFormat.Msi, TargetFormat.Exe)
			packageName = "BaBiQ"
			packageVersion = "1.0.0"
		}
	}
}
