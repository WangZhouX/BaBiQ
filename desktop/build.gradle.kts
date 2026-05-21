import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
	kotlin("jvm") version "2.3.21"
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
	implementation(compose.desktop.currentOs)
	implementation(compose.material3)
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
