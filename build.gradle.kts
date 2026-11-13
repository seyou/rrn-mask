plugins {
    application
}

group = "rrnmask"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime:1.12.1")
    implementation("org.apache.pdfbox:pdfbox:3.0.4")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "rrnmask.Main"
    applicationName = "rrnmask"
    applicationDefaultJvmArgs = listOf("-Xmx3g", "-Djava.awt.headless=true")
}

tasks.test {
    useJUnitPlatform()
}
