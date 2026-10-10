plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    signingConfigs {
        getByName("debug") {
            storeFile = file("subtitler.keystore"); storePassword = "subtitler123"
            keyAlias = "subtitler"; keyPassword = "subtitler123"
        }
    }
    namespace = "com.tttt.subtitler"
    compileSdk = 36
    defaultConfig { applicationId = "com.tttt.subtitler"; minSdk = 26; targetSdk = 34; versionCode = 184; versionName = "1.84"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }   // (v181) معماريات الموبايل بس: بتصغّر مكتبة onnxruntime
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.1")
    implementation("androidx.media3:media3-datasource:1.10.1")
    implementation("androidx.media3:media3-effect:1.10.1")
    implementation("androidx.media3:media3-transformer:1.10.1")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")   // (v172) موديل بصمة الصوت العصبي (اختياري)
}
