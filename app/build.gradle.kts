import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

val localProps = Properties()
rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(localProps::load)

// Firebase (Crashlytics) je volitelný a zdarma — aplikuje se jen tehdy, když
// existuje app/google-services.json (stažený z console.firebase.google.com).
// Bez toho souboru appka jede úplně normálně dál, jen bez crash reportingu.
// Soubor NENÍ v gitu (viz .gitignore).
val googleServicesFile = file("google-services.json")
val firebaseEnabled = googleServicesFile.exists()

if (firebaseEnabled) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

android {
    namespace = "com.haise.jiyu"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.haise.jiyu"
        // Android 13+ (API 33): appka pouziva AGSL RuntimeShader (UpdateProgressOverlay),
        // ktery na nizsich verzich neexistuje.
        minSdk = 33
        targetSdk = 36
        versionCode = 122
        versionName = "1.2.65"
        buildConfigField("String", "SUPABASE_URL", "\"${localProps["SUPABASE_URL"] ?: "https://placeholder.supabase.co"}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${localProps["SUPABASE_ANON_KEY"] ?: "placeholder-anon-key"}\"")
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${localProps["GOOGLE_CLIENT_ID"] ?: "placeholder.apps.googleusercontent.com"}\"")
        buildConfigField("Boolean", "FIREBASE_ENABLED", "$firebaseEnabled")
        buildConfigField("String", "MAL_CLIENT_ID", "\"${localProps["MAL_CLIENT_ID"] ?: ""}\"")
        buildConfigField("String", "ANILIST_CLIENT_ID", "\"${localProps["ANILIST_CLIENT_ID"] ?: ""}\"")

        // Bez tohohle se androidTest NESPUSTI - pouzil by se zastaraly vychozi runner, ktery
        // AndroidX testy neumi, a existujici testy tak byly cele mesice mrtve. Vlastni runner
        // (ne primo AndroidJUnitRunner) je nutny kvuli @HiltAndroidTest, viz HiltTestRunner.
        testInstrumentationRunner = "com.haise.jiyu.HiltTestRunner"

    }

    // Vlastni release klic (od v1.2.29) - do tohoto data se release podepisoval defaultnim
    // DEBUG klicem (stejny na kazde instalaci Android Studia na svete), protoze prechod na
    // vlastni klic si vynuti jednorazovou rucni odinstalaci/instalaci appky. Ta cena uz byla
    // zaplacena (viz CHANGELOG v1.2.29); dal uz kazdy update nese realnou zaruku podpisu.
    // Klic (release.keystore.jks) a jeho heslo NEJSOU v gitu (viz .gitignore/local.properties*),
    // zalohovane mimo repo - jejich ztrata by znamenala uplne stejny problem znovu.
    val releaseKeystorePath = localProps["RELEASE_KEYSTORE_PATH"] as String?
    val releaseKeystoreFile = releaseKeystorePath?.let { rootProject.file(it) }?.takeIf { it.exists() }

    signingConfigs {
        if (releaseKeystoreFile != null) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = localProps["RELEASE_KEYSTORE_PASSWORD"] as String
                keyAlias = localProps["RELEASE_KEY_ALIAS"] as String
                // PKCS12 keystory (vychozi format noveho keytool) nema oddelene store/key
                // heslo - pri generovani klice keytool druhou hodnotu tise ignoruje.
                keyPassword = localProps["RELEASE_KEYSTORE_PASSWORD"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Bez release.keystore.jks (cerstvy git clone bez local.properties) spadne na
            // DEBUG klic, aby ./gradlew assembleRelease porad slo spustit - jen vysledny APK
            // pak neni ten, ktery appka skutecne distribuuje (viz GitHub Actions, ktery
            // assembleRelease vubec nespousti).
            signingConfig = if (releaseKeystoreFile != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Bez tohohle se .onnx assety v APK deflate-komprimuji - AssetManager by je pak musel
    // před čtením plně dekomprimovat, což je zbytečné navíc u velkých binárních modelů a
    // dělá ensureModelFileFromAsset (viz MangaOcrPipeline) o to pomalejší/pamětově dražší
    // (audit finding Critical #1b). AGP 8+ DSL - `aaptOptions` je starší ekvivalent.
    androidResources {
        noCompress += "onnx"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

// Od AGP 8.13 je `android.kotlinOptions` zastarale - nastaveni kompilatoru patri sem.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
}

dependencies {
    // Core / Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // Hilt (dependency injection)
    implementation("com.google.dagger:hilt-android:2.57.2")
    ksp("com.google.dagger:hilt-android-compiler:2.57.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room (lokální databáze)
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Síť a parsování
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("org.json:json:20240303")

    // Vlastní ikonová sada (Tabler Icons) - náhrada za generické Material ikony
    implementation("br.com.devsrsouza.compose.icons:tabler-icons:1.1.1")

    // Obrázky
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Stahování na pozadí (offline kapitoly)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // Nastavení (DataStore)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // OCR – ML Kit Text Recognition (funguje offline, bundled model)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")

    // ONNX Runtime — spouští natrénované YOLOv8 modely (detekce/segmentace bublin, viz
    // BubbleBoxDetector/BubbleMaskSegmenter) přímo na zařízení, žádné API/server.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

    // Supabase — cloud sync + auth (2.0.3 je poslední verze s Kotlin 1.9.x)
    implementation("io.github.jan-tennert.supabase:postgrest-kt:2.0.3")
    implementation("io.github.jan-tennert.supabase:gotrue-kt:2.0.3")
    implementation("io.ktor:ktor-client-okhttp:2.3.9")

    // Google Sign-In via Credential Manager
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Kotlinx serialization — pro Supabase DTOs
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // QR kód generování (bez Activity, pure Java)
    implementation("com.google.zxing:core:3.5.3")

    // AppCompat — potřebné pro AppCompatDelegate.setApplicationLocales()
    implementation("androidx.appcompat:appcompat:1.7.0")

    // ML Kit on-device language identification + translation (záloha bez API klíče)
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")

    // Jetpack Security — šifrované úložiště pro tracker tokeny/hesla (MAL/Kitsu/MangaUpdates).
    // Dlouho existovala jen jako alpha (a byl tu komentář, že to tak nejspíš zůstane), ale
    // 1.1.0 stable mezitím vyšla. API je stejné, žádná úprava kódu nebyla potřeba.
    implementation("androidx.security:security-crypto:1.1.0")

    // DocumentFile — zápis do uživatelem vybrané SAF složky (např. lokálně synchronizovaná Google Drive/Dropbox složka)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // App Widget (Glance — Compose-based home screen widget)
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    // Firebase Crashlytics + Analytics (zdarma) — knihovny se přidávají vždy,
    // ale reálně se inicializují (viz JiyuApp) jen když je FIREBASE_ENABLED.
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-analytics")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // ViewModely zavisi na konkretnich (final) tridach repozitaru bez rozhrani, takze
    // rucne psane fake implementace nejdou - mockk umi finalni Kotlin tridy zastoupit.
    // Jen pro testy, do APK se nedostane.
    testImplementation("io.mockk:mockk:1.13.11")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.12.01"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.navigation:navigation-testing:2.9.8")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.57.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    kspAndroidTest("com.google.dagger:hilt-android-compiler:2.57.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
