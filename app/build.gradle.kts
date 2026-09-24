plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Which code a device result measured (PLAN §7.1 rule 3): HKSelfTest prints these first.
fun git(vararg args: String): String = try {
    val p = ProcessBuilder(listOf("git") + args)
        .directory(rootProject.projectDir)
        .redirectErrorStream(false)
        .start()
    val out = p.inputStream.bufferedReader().readText().trim()
    if (p.waitFor() == 0) out else ""
} catch (e: Exception) {
    ""
}
val gitBranch = git("symbolic-ref", "--short", "-q", "HEAD").ifEmpty { "detached" }
val gitCommit = git("rev-parse", "--short=12", "HEAD").ifEmpty { "uncommitted" } +
    (if (git("status", "--porcelain", "--untracked-files=no").isNotEmpty()) "-dirty" else "")

android {
    namespace = "com.tropicalstream.hammerklavier"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tropicalstream.hammerklavier"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-rc1"

        buildConfigField("String", "GIT_BRANCH", "\"$gitBranch\"")
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // The measured build (PLAN §8.1): not debuggable, not minified, so the DSP is timed as
            // shipped; signed with the debug keystore so tools/device/run.sh can install it.
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Opus units, env.bin, map.json, catalog.json and MIDI are read in place (mmap / AssetManager).
    androidResources {
        noCompress += listOf("opus", "bin", "json", "mid", "midi", "kar")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += listOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

// Friendly APK names: Hammerklavier-debug.apk, Hammerklavier-release.apk
base {
    archivesName.set("Hammerklavier")
}

dependencies {
    implementation(project(":core"))

    // No RayNeo SDK: the platform needs only the com.rayneo.mercury.app manifest meta-data.
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("androidx.profileinstaller:profileinstaller:1.4.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20180813")
    testImplementation(testFixtures(project(":core")))
}
