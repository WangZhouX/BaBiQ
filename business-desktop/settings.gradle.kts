pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
        google()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "huitai-business-desktop"
include(
    ":app",
    ":presentation-core",
    ":application-action-core",
    ":agent-client-core",
    ":security-audit-core",
    ":framework-demo",
)
