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
    defaultConfig { applicationId = "com.tttt.subtitler"; minSdk = 26; targetSdk = 34; versionCode = 174; versionName = "1.74" }
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
    implementation("com.google.mlkit:text-recognition:16.0.1")            // (v174) OCR لاتيني/إنجليزي على الجهاز
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")                    // ترجمة على الجهاز → عربي
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")   // (v172) موديل بصمة الصوت العصبي (اختياري)
}
