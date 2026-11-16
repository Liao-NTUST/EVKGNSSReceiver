plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.evkgnssreceiver"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.evkgnssreceiver"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf(
                    "androidx.**",
                    "kotlin.**",
                    "kotlinx.**"
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // 避免 MQTT 函式庫打包時發生資源檔案衝突
    packaging {
        resources {
            excludes += listOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties"
            )
        }
    }
}

dependencies {

    // Android Studio 原本的函式庫
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // MQTT：Android 傳送 JSON 到 Windows PC
    implementation("com.hivemq:hivemq-mqtt-client:1.4.2")

    // USB Serial：Android 讀取 EVK-X20P
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")

    // 測試函式庫
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}