plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.usbdisplay.tester"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.usbdisplay.tester"
        minSdk = 26          // Android 8.0, с запасом для ТВ на Google TV
        targetSdk = 34
        versionCode = 18
        versionName = "0.17"
    }

    buildTypes {
        release {
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
}

// Зависимостей нарочно нет: тестер должен быть максимально лёгким.
dependencies {
}
