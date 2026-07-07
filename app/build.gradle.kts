import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

val localProps = Properties()
rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(localProps::load)

android {
    namespace = "com.haise.jiyu"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.haise.jiyu"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "GROQ_API_KEY", "\"${localProps["GROQ_API_KEY"] ?: ""}\"")
        buildConfigField("String", "SUPABASE_URL", "\"${localProps["SUPABASE_URL"] ?: "https://placeholder.supabase.co"}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${localProps["SUPABASE_ANON_KEY"] ?: "placeholder-anon-key"}\"")
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${localProps["GOOGLE_CLIENT_ID"] ?: "placeholder.apps.googleusercontent.com"}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Core / Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // Hilt (dependency injection)
    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-android-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room (lokální databáze)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Síť a parsování
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("org.json:json:20240303")

    // Material icons extended (Download, ArrowDownward, Translate, atd.)
    implementation("androidx.compose.material:material-icons-extended")

    // Obrázky
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Stahování na pozadí (offline kapitoly)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // Nastavení (DataStore)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // OCR – ML Kit Text Recognition (funguje offline, bundled model)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.0")

    // Supabase — cloud sync + auth (2.0.3 je poslední verze s Kotlin 1.9.x)
    implementation("io.github.jan-tennert.supabase:postgrest-kt:2.0.3")
    implementation("io.github.jan-tennert.supabase:gotrue-kt:2.0.3")
    implementation("io.ktor:ktor-client-okhttp:2.3.9")

    // Google Sign-In via Credential Manager
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Kotlinx serialization — pro Supabase DTOs
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // QR kód generování (bez Activity, pure Java)
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.navigation:navigation-testing:2.7.7")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.51.1")
    kspAndroidTest("com.google.dagger:hilt-android-compiler:2.51.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
