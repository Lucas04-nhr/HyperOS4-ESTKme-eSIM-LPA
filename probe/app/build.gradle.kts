import java.util.Properties

plugins { alias(libs.plugins.android.application) }

// Signing comes from the SIGNING_* environment variables (CI) or from signing.* in
// probe/local.properties. Without either, the build falls back to the debug key.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty("signing.$key")?.takeIf { it.isNotBlank() }
val signingStoreFile = signingValue("storeFile", "SIGNING_STORE_FILE")
    ?.replaceFirst(Regex("^~(?=/|$)"), System.getProperty("user.home"))

android {
    namespace = "io.github.local.esimh4probe"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }
    defaultConfig {
        applicationId = "io.github.jabe1122.esimh4probe"
        minSdk = 33
        targetSdk = 35
        versionCode = 601
        versionName = "0.6.1"
    }
    signingConfigs {
        if (signingStoreFile != null) {
            create("local") {
                storeFile = file(signingStoreFile)
                storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            // R8: the mapping file is build/outputs/mapping/release/mapping.txt
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo.include = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        if (signingStoreFile != null) {
            getByName("debug") { signingConfig = signingConfigs.getByName("local") }
            getByName("release") { signingConfig = signingConfigs.getByName("local") }
        }
    }
    splits {
        // One APK per ABI plus the universal one: <module>-universal-, -arm64-v8a- and
        // -armeabi-v7a-<buildType>.apk. The module zip ships the arm64-v8a APK.
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }
    // No x86 support: keeps x86 / x86_64 native libraries out of the universal APK too
    packaging {
        jniLibs.excludes += listOf("lib/x86/**", "lib/x86_64/**")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        disable += "BlockedPrivateApi"
    }
}
dependencies {
    implementation(project(":ui"))
    compileOnly(libs.libxposed.api)
}
