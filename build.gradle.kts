plugins {
    kotlin("jvm") version "2.2.21"
}

group = "com.github.kzw200015"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.netty:netty-all:4.2.7.Final")
    implementation("ch.qos.logback:logback-classic:1.5.21")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
