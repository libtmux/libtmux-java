plugins {
    kotlin("jvm") version "2.4.10"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("io.github.libtmux:libtmux-kotlin:0.1.1-SNAPSHOT")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
kotlin { jvmToolchain(25) }

application { mainClass.set(providers.gradleProperty("exampleMain")) }
