import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}
val releaseStorePath = keystoreProperties.getProperty("storeFile")
val releaseStoreFile: File? =
    if (releaseStorePath.isNullOrBlank()) {
        null
    } else {
        file(releaseStorePath).takeIf { it.isFile }
    }

android {
    namespace = "com.omoai.simpleuvcstreamer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.omoai.simpleuvcstreamer"
        // API 28+: libhv (FORTIFY) needs __sendto_chk; keep minSdk in sync with NDK platform.
        minSdk = 28
        targetSdk = 37
        versionCode = providers.gradleProperty("releaseVersionCode").orNull?.toIntOrNull() ?: 2
        versionName = providers.gradleProperty("releaseVersionName").orNull ?: "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                val vcpkgRoot = System.getenv("VCPKG_ROOT") ?: "${System.getProperty("user.home")}/vcpkg"
                val ndkDir = System.getenv("ANDROID_NDK_HOME")
                    ?: System.getenv("ANDROID_HOME")?.let { "$it/ndk/28.2.13676358" }
                    ?: "${System.getProperty("user.home")}/Library/Android/sdk/ndk/28.2.13676358"

                arguments += listOf(
                    "-DVCPKG_ROOT=$vcpkgRoot",
                    "-DCMAKE_TOOLCHAIN_FILE=$vcpkgRoot/scripts/buildsystems/vcpkg.cmake",
                    "-DVCPKG_MANIFEST_MODE=ON",
                    "-DVCPKG_CHAINLOAD_TOOLCHAIN_FILE=$ndkDir/build/cmake/android.toolchain.cmake",
                    "-DANDROID_PLATFORM=android-28",
                    "-DANDROID_STL=c++_shared"
                )
            }
        }
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // AGP 9.3+: enables R8 code shrinking/obfuscation + resource shrinking.
            optimization {
                enable = true
            }
            signingConfig = if (releaseStoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            ndk {
                // Upload native symbols to Play / keep crash stacks useful.
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
        debug {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
