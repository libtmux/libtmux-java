plugins {
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("io.github.libtmux:libtmux:0.1.2-SNAPSHOT")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }

application { mainClass.set(providers.gradleProperty("exampleMain")) }
