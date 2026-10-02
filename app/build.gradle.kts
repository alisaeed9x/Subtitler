plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.tttt.subtitler"
    compileSdk = 34
    defaultConfig { applicationId = "com.tttt.subtitler"; minSdk = 26; targetSdk = 34; versionCode = 14; versionName = "0.14-HTMLUI-MATCH" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")
}
