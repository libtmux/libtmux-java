plugins {
    scala
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("io.github.libtmux:libtmux-scala-cats_3:0.1.0-SNAPSHOT")
    implementation("org.scala-lang:scala3-library_3:3.9.0")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }

application { mainClass.set(providers.gradleProperty("exampleMain")) }
