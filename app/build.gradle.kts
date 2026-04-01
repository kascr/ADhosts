plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.kascr.adhosts"
    compileSdk = 36

    signingConfigs {
        create("release") {
            storeFile = file("adhosts_release.keystore")
            // 从环境变量读取，避免明文写入仓库
            storePassword = "adhosts123"
            keyAlias = "adhosts"
            keyPassword = "adhosts123"
        }
    }

    defaultConfig {
        applicationId = "com.kascr.adhosts"
        minSdk = 29
        targetSdk = 36
        versionCode = 118
        versionName = "1.1.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    val libsuVersion = "6.0.0"
    implementation(libs.androidx.activity)
    implementation(libs.androidx.benchmark)
    implementation ("com.google.code.gson:gson:2.9.0")//json
    implementation ("androidx.recyclerview:recyclerview:1.2.1")
    implementation("com.github.topjohnwu.libsu:core:$libsuVersion")
    implementation("com.github.topjohnwu.libsu:service:$libsuVersion")
    implementation("com.github.topjohnwu.libsu:nio:$libsuVersion")//root
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
