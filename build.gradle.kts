plugins {
    kotlin("jvm") version "2.2.21"
    application
}

group = "com.github.kzw200015"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    val kotlinVersion = "2.2.21"
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    implementation("io.netty:netty-all:4.2.7.Final")
    implementation("info.picocli:picocli:4.7.7")
    val fastjsonVersion = "2.0.60"
    implementation("com.alibaba.fastjson2:fastjson2:$fastjsonVersion")
    implementation("com.alibaba.fastjson2:fastjson2-kotlin:$fastjsonVersion")
    implementation("ch.qos.logback:logback-classic:1.3.16")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(8)
}

application {
    mainClass.set("MainKt.main")
}

tasks.test {
    useJUnitPlatform()
}
