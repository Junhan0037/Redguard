plugins {
    kotlin("jvm") version "2.0.21"
    id("io.gatling.gradle") version "3.10.5"
}

repositories {
    mavenCentral()
}

dependencies {
    gatlingImplementation(kotlin("stdlib"))
}

kotlin {
    jvmToolchain(17)
}

gatling {
    logLevel = "INFO"
}
