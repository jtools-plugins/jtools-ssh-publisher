pluginManagement {
    repositories {
        mavenLocal()
        maven("https://maven.aliyun.com/repository/public/")
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.5.0"
}
rootProject.name = "jtools-ssh-publisher"

