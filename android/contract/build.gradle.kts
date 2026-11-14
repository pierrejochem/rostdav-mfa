plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// The build runs on whatever JDK is installed (25 here); the classes target Java 17, like the app.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.test {
    // the shared vectors of the contract repository: the same file the Rust tests read
    systemProperty(
        "vectors.path",
        rootProject.projectDir.resolve("../contract/vectors/v1.json").canonicalPath,
    )
}
