import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core — every pure package (PLAN §2.2). Kotlin JVM only: no Android plugin runs here, so the
// compiler itself keeps android.* out. Pure code may import kotlin.*, java.* (not java.awt) and
// org.json.* (compileOnly: Android provides org.json at run time); tools/check_purity.sh greps too.
plugins {
    kotlin("jvm")                 // resolves from the kotlin-gradle-plugin 2.0.21 on the root classpath
    `java-test-fixtures`          // core/src/testFixtures: AwtPainter, AllocProbe, MeshRaster (java.awt allowed)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    compileOnly("org.json:json:20180813")

    testFixturesImplementation("junit:junit:4.13.2")
    testFixturesCompileOnly("org.json:json:20180813")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20180813")
}

tasks.withType<Test>().configureEach {
    // HotSpot's scalar replacement would hide allocations that ART makes (PLAN §7.1 rule 6).
    jvmArgs("-XX:-DoEscapeAnalysis", "-XX:-EliminateAllocations")
    maxHeapSize = "1g"
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
