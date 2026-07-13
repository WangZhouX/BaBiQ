import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
    implementation(project(":huitai-integration-core"))
    implementation(project(":security-audit-core"))
    implementation(project(":framework-demo"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
kotlin { jvmToolchain(21) }

compose.desktop {
    application {
        mainClass = "com.wzx.huitai.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "HuitaiBusinessDesktop"
            packageVersion = "0.1.0"
            includeAllModules = true
        }
    }
}
