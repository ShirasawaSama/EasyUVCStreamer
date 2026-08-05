plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.omoai.simpleuvcstreamer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.omoai.simpleuvcstreamer"
        minSdk = 25
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                val vcpkgRoot = System.getenv("VCPKG_ROOT") ?: "${System.getProperty("user.home")}/vcpkg"
                val ndkDir = "/Users/shirasawa/Library/Android/sdk/ndk/28.2.13676358"

                arguments += listOf(
                    "-DVCPKG_ROOT=$vcpkgRoot",
                    "-DCMAKE_TOOLCHAIN_FILE=$vcpkgRoot/scripts/buildsystems/vcpkg.cmake",
                    "-DVCPKG_MANIFEST_MODE=ON",
                    "-DVCPKG_CHAINLOAD_TOOLCHAIN_FILE=$ndkDir/build/cmake/android.toolchain.cmake",
                    "-DANDROID_PLATFORM=android-24",
                    "-DANDROID_STL=c++_shared"
                )
            }
        }
    }

    buildTypes {
        release {
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