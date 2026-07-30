import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import java.nio.file.Path

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
dependencies {
    implementation(project(":presentation-core"))
    implementation(project(":application-action-core"))
    implementation(project(":agent-client-core"))
    implementation(project(":security-audit-core"))
    implementation(project(":framework-demo"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("io.ktor:ktor-client-core:3.5.0")
    implementation("io.ktor:ktor-client-cio:3.5.0")
    implementation("io.ktor:ktor-client-websockets:3.5.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.ktor:ktor-server-core:3.5.0")
    testImplementation("io.ktor:ktor-server-cio:3.5.0")
    testImplementation("io.ktor:ktor-server-websockets:3.5.0")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.1")
}
kotlin { jvmToolchain(21) }

val backendProjectDir = rootProject.projectDir.parentFile.resolve("backend")
val backendJar = backendProjectDir.resolve("target/babiq-server-0.0.1-SNAPSHOT.jar")
val backendMavenRepository = File(System.getProperty("user.home"), ".m2/repository")
val preparedAppResourcesRoot = layout.buildDirectory.dir("preparedAppResources")
val bundledBackendRelativePath = "common/backend/babiq-server.jar"

val packageBusinessBackendJar by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds the business-profile backend jar bundled with the desktop application."
    workingDir(backendProjectDir)
    commandLine(
        backendProjectDir.resolve("mvnw.cmd").absolutePath,
        "-o",
        "-Dmaven.repo.local=${backendMavenRepository.absolutePath}",
        "-DskipTests",
        "package",
    )
    inputs.files(
        fileTree(backendProjectDir.resolve("src")),
        backendProjectDir.resolve("pom.xml"),
        backendProjectDir.resolve("mvnw.cmd"),
        backendProjectDir.resolve(".mvn/wrapper/maven-wrapper.properties"),
    )
    outputs.file(backendJar)
}

val prepareBundledBusinessBackend by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Copies the built backend as common/backend/babiq-server.jar."
    dependsOn(packageBusinessBackendJar)
    from(backendJar)
    into(preparedAppResourcesRoot.map { it.dir("common/backend") })
    rename { "babiq-server.jar" }
    inputs.property("bundledBackendRelativePath", bundledBackendRelativePath)
}

tasks.named<Test>("test") {
    dependsOn(packageBusinessBackendJar)
    inputs.file(backendJar)
        .withPropertyName("businessBackendJar")
        .withPathSensitivity(PathSensitivity.NONE)
    systemProperty("huitai.backend.jar", backendJar.absolutePath)
}

val runBusinessBackendDevelopment by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Runs only the standalone business backend and keeps its logs in this console."
    dependsOn(packageBusinessBackendJar, "classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.wzx.huitai.desktop.runtime.BusinessBackendDevelopmentRunnerKt")
    systemProperty("huitai.backend.jar", backendJar.absolutePath)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

val runBusinessFrontendDevelopment by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Runs only the Compose frontend and connects to an already running backend."
    dependsOn("classes")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.wzx.huitai.desktop.MainKt")
    environment("HUITAI_DESKTOP_EXTERNAL_BACKEND", "1")
    environment(
        "HUITAI_DESKTOP_CONFIG_FILE",
        rootProject.projectDir.resolve("config/business-desktop-development.properties").absolutePath,
    )
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

compose.desktop {
    application {
        mainClass = "com.wzx.huitai.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "翔鸟律智桌面端"
            packageVersion = "0.1.0"
            description = "翔鸟律智桌面端"
            vendor = "翔鸟律智"
            includeAllModules = true
            appResourcesRootDir.set(preparedAppResourcesRoot)
            windows {
                iconFile.set(project.file("src/main/resources/brand/xiangniao.ico"))
                shortcut = true
                menu = true
                menuGroup = "翔鸟律智"
                upgradeUuid = "5938a1de-244d-41c4-b193-064654515e62"
            }
        }
    }
}

val retainRuntimeJavaExecutable by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Restores the Java launcher required by the bundled backend child process."
    dependsOn("createRuntimeImage")
    val executableName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "java.exe"
    } else {
        "java"
    }
    from(Path.of(System.getProperty("java.home"), "bin", executableName))
    into(layout.buildDirectory.dir("compose/tmp/main/runtime/bin"))
}

val nativePackageTasks = setOf(
    "prepareAppResources",
    "createRuntimeImage",
    "createDistributable",
    "createReleaseDistributable",
    "packageDistributionForCurrentOS",
    "packageMsi",
    "packageExe",
    "packageReleaseDistributionForCurrentOS",
    "packageReleaseMsi",
    "packageReleaseExe",
)

tasks.matching { it.name in nativePackageTasks }.configureEach {
    dependsOn(prepareBundledBusinessBackend)
}

tasks.matching {
    it.name in nativePackageTasks && it.name !in setOf("prepareAppResources", "createRuntimeImage")
}.configureEach {
    dependsOn(retainRuntimeJavaExecutable)
}

val smokePackagedDistribution by tasks.registering(Exec::class) {
    group = "verification"
    description = "Extracts and smoke-tests the packaged Windows desktop distribution."
    dependsOn("createDistributable", "packageMsi", "packageExe")
    onlyIf { System.getProperty("os.name").startsWith("Windows", ignoreCase = true) }
    workingDir(rootProject.projectDir)
    commandLine(
        "powershell.exe",
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        rootProject.projectDir.resolve("scripts/smoke-packaged-distribution.ps1").absolutePath,
        "-AppBuildDir",
        layout.buildDirectory.get().asFile.absolutePath,
        "-RepositoryRoot",
        rootProject.projectDir.parentFile.absolutePath,
    )
}
