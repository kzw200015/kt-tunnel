plugins {
    val kotlinVersion = "2.3.0"
    kotlin("jvm") version kotlinVersion
    kotlin("kapt") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion
    application
    id("com.gradleup.shadow") version "9.2.2"
    id("org.graalvm.buildtools.native") version "0.11.3"
}

group = "com.github.kzw200015"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(8)
}

dependencies {
    val kotlinVersion = "2.3.0"
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    val nettyVersion = "4.2.9.Final"
    implementation("io.netty:netty-all:$nettyVersion")
    implementation("io.netty:netty-pkitesting:$nettyVersion")
    implementation("org.bouncycastle:bctls-jdk18on:1.83")
    val picocliVersion = "4.7.7"
    implementation("info.picocli:picocli:$picocliVersion")
    kapt("info.picocli:picocli-codegen:$picocliVersion")
    val kotlinxSerializationVersion = "1.9.0"
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")
    implementation("ch.qos.logback:logback-classic:1.5.22")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("MainKt")
}

tasks.shadowJar {
    archiveClassifier.set("standalone")
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    mergeServiceFiles()
}

graalvmNative {
    metadataRepository {
        enabled.set(true)
    }
    binaries {
        named("main") {
            buildArgs.addAll(
                "-H:+ReportExceptionStackTraces",
                "--initialize-at-run-time=io.netty.handler.ssl.util.ThreadLocalInsecureRandom",
            )
        }
    }
    toolchainDetection = false
}

tasks.test {
    useJUnitPlatform()
}
