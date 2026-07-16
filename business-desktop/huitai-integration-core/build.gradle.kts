plugins { kotlin("jvm"); kotlin("plugin.serialization") }
dependencies {
    implementation(project(":application-action-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("io.ktor:ktor-client-core:3.5.0")
    implementation("io.ktor:ktor-client-cio:3.5.0")
    implementation("io.ktor:ktor-client-websockets:3.5.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.ktor:ktor-client-mock:3.5.0")
    testImplementation("io.ktor:ktor-server-test-host:3.5.0")
    testImplementation("io.ktor:ktor-server-core:3.5.0")
    testImplementation("io.ktor:ktor-server-cio:3.5.0")
    testImplementation("io.ktor:ktor-server-websockets:3.5.0")
}
kotlin { jvmToolchain(21) }
