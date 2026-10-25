import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Module « Lecture » : Media3 ExoPlayer + MediaLibraryService (notification, écran
// verrouillé, casque Bluetooth, Android Auto), position enregistrée toutes les 10 s.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.julienmiquel.audiotheque.player"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core"))
    api(libs.androidx.media3.exoplayer)
    api(libs.androidx.media3.session)
    api(libs.kotlinx.coroutines.guava)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
