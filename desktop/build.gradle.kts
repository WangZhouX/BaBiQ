import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask

plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.serialization") version "2.3.21"
	id("org.jetbrains.compose") version "1.11.0"
	id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
}

group = "com.wzx.babiq.desktop"
version = "0.0.1-SNAPSHOT"

val backendProjectDir = layout.projectDirectory.dir("../backend")
val backendJarFile = backendProjectDir.file("target/babiq-server-0.0.1-SNAPSHOT.jar")
val bundledAppResourcesDir = layout.buildDirectory.dir("generated/app-resources")
val bundledBackendDir = bundledAppResourcesDir.map { it.dir("common/backend") }

val packageBackendJar by tasks.registering(Exec::class) {
	group = "distribution"
	description = "Builds the Spring Boot backend jar that is bundled into the desktop installer."
	workingDir = backendProjectDir.asFile
	commandLine("cmd", "/c", "mvnw.cmd", "-DskipTests", "package")
}

val prepareBundledBackend by tasks.registering(Copy::class) {
	group = "distribution"
	description = "Copies the backend jar into Compose Desktop app resources."
	dependsOn(packageBackendJar)
	from(backendJarFile)
	into(bundledBackendDir)
	rename { "babiq-server.jar" }
}

repositories {
	mavenCentral()
	google()
	maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
	val ktorVersion = "3.5.0"
	val kotlinxSerializationVersion = "1.11.0"
	val kotlinxCoroutinesVersion = "1.11.0"
	val slf4jVersion = "2.0.17"
	val composeVersion = "1.11.0"

	implementation(compose.desktop.currentOs)
	implementation(compose.material3)
	implementation("org.jetbrains.compose.components:components-resources:$composeVersion")
	implementation("io.ktor:ktor-client-core:$ktorVersion")
	implementation("io.ktor:ktor-client-cio:$ktorVersion")
	implementation("io.ktor:ktor-client-websockets:$ktorVersion")
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:$kotlinxCoroutinesVersion")
	runtimeOnly("org.slf4j:slf4j-simple:$slf4jVersion")

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
			includeAllModules = true
			modules("ALL-MODULE-PATH")
			appResourcesRootDir.set(bundledAppResourcesDir)
		}
	}
}

compose.resources {
	packageOfResClass = "com.wzx.babiq.desktop.generated.resources"
}

tasks.matching { it.name == "prepareAppResources" }.configureEach {
	dependsOn(prepareBundledBackend)
}

tasks.withType<AbstractJLinkTask>().configureEach {
	@Suppress("UNCHECKED_CAST")
	val stripNativeCommands = javaClass.methods
		.first { it.name == "getStripNativeCommands\$compose" }
		.invoke(this) as org.gradle.api.provider.Property<Boolean>
	stripNativeCommands.set(false)
}
