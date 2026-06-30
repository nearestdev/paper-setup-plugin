plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "1.3.1"
    `maven-publish`
}

group = "io.github.gateopenerz"
version = "1.2.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.13.1")
}

gradlePlugin {
    website.set("https://github.com/gateopenerz/paper-setup-plugin")
    vcsUrl.set("https://github.com/gateopenerz/paper-setup-plugin.git")
    plugins {
        create("paperSetupServer") {
            id = "io.github.gateopenerz.paper-server"
            implementationClass = "com.gateopenerz.paperserver.PaperServerPlugin"
            displayName = "Multi-Server Setup Plugin"
            description = "Downloads & runs Paper, Purpur, Velocity, Folia, and Advanced Slime Paper with configurable jvm arguments and hooks"
            tags.set(listOf("minecraft", "paper", "purpur", "velocity", "folia", "advanced-slime-paper", "server"))
        }
    }
}