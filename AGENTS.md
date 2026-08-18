# Agent Notes for Jiyu

## Build Environment

- This is an Android/Kotlin app using Gradle + Hilt/KSP.
- The Gradle wrapper requires **JDK 17** (OpenJDK 25 / Android Studio's bundled JBR will fail during unit tests). Use a JDK 17 installation and set `JAVA_HOME` before running Gradle.
- Android SDK is expected at `C:\Users\conta\AppData\Local\Android\Sdk`.

## Useful Commands

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:ANDROID_HOME = 'C:\Users\conta\AppData\Local\Android\Sdk'

# Compile
.\gradlew.bat :app:compileDebugKotlin

# Build debug APK
.\gradlew.bat :app:assembleDebug

# Run translation + reader unit tests (pure JVM, no Robolectric issues)
.\gradlew.bat :app:testDebugUnitTest --tests "com.haise.jiyu.translate.*" --tests "com.haise.jiyu.ui.reader.*"

# Run all unit tests (some Robolectric database tests may fail in this environment)
.\gradlew.bat :app:testDebugUnitTest
```

## Notes

- `com.haise.jiyu.translate.*` and `com.haise.jiyu.ui.reader.ReaderViewModelBatchTranslateTest` / `ReaderViewModelIncognitoTest` are pure JVM tests and pass with JDK 17.
- Some DAO/Robolectric tests fail with `java.lang.UnsupportedOperationException at DefaultSdkProvider.java:170` in this headless environment; they are unrelated to the translation/bubble fitting logic.
