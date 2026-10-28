import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Interface Jetpack Compose / Material 3 : Bibliothèque, Fiche livre, Lecteur, Synchro, Réglages.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ID client OAuth « Application Web » pour Credential Manager (facultatif), lu dans
// local.properties (non versionné) ou en propriété Gradle -Paudiotheque.webClientId=…
val localProperties = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
        .asText.orNull?.let { load(it.reader()) }
}

/** Propriété facultative : local.properties, sinon -P<nom>=…, sinon vide. */
fun optionalProperty(name: String): String =
    (localProperties.getProperty(name) ?: providers.gradleProperty(name).orNull ?: "").trim()

val webClientId: String = optionalProperty("audiotheque.webClientId")

// Recherche d'images Google (API Custom Search JSON) pour les pochettes introuvables dans les
// catalogues : clé d'API et identifiant du moteur programmable (facultatifs).
val customSearchKey: String = optionalProperty("audiotheque.customSearchKey")
val customSearchCx: String = optionalProperty("audiotheque.customSearchCx")

android {
    namespace = "com.julienmiquel.audiotheque"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.julienmiquel.audiotheque"
        minSdk = 33
        targetSdk = 36
        versionCode = 4
        versionName = "1.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "WEB_CLIENT_ID", "\"$webClientId\"")
        buildConfigField("String", "CUSTOM_SEARCH_KEY", "\"$customSearchKey\"")
        buildConfigField("String", "CUSTOM_SEARCH_CX", "\"$customSearchCx\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Usage personnel (APK installé à la main) : signé avec la clé de debug pour
            // n'enregistrer qu'une seule empreinte SHA-1 dans le client OAuth Android.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":library"))
    implementation(project(":sync"))
    implementation(project(":player"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
