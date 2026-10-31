plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// (v569) sherpa-onnx و ML Kit اتشالوا من الـ APK (كانوا بيزوّدوه ~100 ميجا) — التفريغ على الجهاز Whisper بس.
android {
    signingConfigs {
        getByName("debug") {
            storeFile = file("subtitler.keystore"); storePassword = "subtitler123"
            keyAlias = "subtitler"; keyPassword = "subtitler123"
        }
    }
    namespace = "com.tttt.subtitler"
    compileSdk = 36
    defaultConfig { applicationId = "com.tttt.joyboy"; minSdk = 26; targetSdk = 34; versionCode = (System.getenv("JB_VERSION_CODE")?.toIntOrNull() ?: (System.currentTimeMillis() / 60000L).toInt()); versionName = "6.79"   // (v656) versionCode = وقت البناء بالدقايق: أي بيلد (حتى من سورس قديم) كوده أعلى من اللي قبله ← أندرويد يقبل التثبيت فوق الأحدث من غير حذف ولا فقد بيانات (نفس الـkeystore)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild { cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release"); abiFilters += listOf("arm64-v8a", "armeabi-v7a") } }   // (v253) whisper.cpp + (v307) substem: arm64 بس (armeabi-v7a من غير أوفلاين)   // (v181) معماريات الموبايل بس: بتصغّر مكتبة onnxruntime
    }
    buildTypes {
        release {
            // (v458) R8 + تصغير الموارد: الـ APK كان بيطلع من غير أي تصغير. القواعد محافظة جدًا
            // (proguard-rules.pro بيحفظ كل كلاسات التطبيق والمكتبات الانعكاسية/JNI) — جرّب الإصدار كويس قبل النشر.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug { isMinifyEnabled = false }
    }
    // (v460) تقسيم حسب المعمارية: الـ APK الواحد كان شايل مكتبات arm64 + armeabi-v7a مع بعض (~100MB).
    // دلوقتي كل معمارية ليها APK لوحده (اللي هتنزّله لجهازك هو اللي فيه arm64-v8a في اسمه — أصغر بكتير)،
    // واليونيفرسال لسه بيتبني كاحتياطي. armeabi-v7a من غير مكتبة Whisper الأوفلاين كالعادة (v253).
    splits {
        abi {
            isEnable = false   // (v474) APK واحد universal لكل بيلد — مفيش تقسيم ABI
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }   // (v327) NewPipeExtractor بيستخدم List.of وغيره
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }   // (v421) كوتلن 2.4.0 — نفس نسخة stdlib اللي litertlm بتجيبها (كان 1.9.24 فالبناء بيقع)
    testOptions { unitTests { isReturnDefaultValues = true } }   // (v589 T4) استدعاءات android.* في اختبارات JVM ترجّع قيم افتراضية بدل ما ترمي
    packaging { resources { excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/DEPENDENCIES", "/META-INF/*.version") } }   // (v325) NewPipeExtractor
}
dependencies {
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.1")
    implementation("androidx.media3:media3-datasource:1.10.1")
    implementation("androidx.media3:media3-database:1.10.1")   // (v564) كاش فيديو الموبايل على التلفزيون
    implementation("androidx.media3:media3-effect:1.10.1")
    implementation("androidx.media3:media3-transformer:1.10.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")   // (v598) ماسح QR داخل التطبيق (اقتران التلفزيون + دخول يوتيوب)
    implementation("androidx.core:core:1.13.1")   // (v310) FileProvider للفايل مانجر
    implementation("com.github.bumptech.glide:glide:4.16.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")   // (v327)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")   // (v418) WebSocket لـ Edge TTS (دبلجة عربي)
    testImplementation("junit:junit:4.13.2")            // (v589 T4)
    testImplementation("org.json:json:20240303")        // org.json الحقيقي بدل stub بتاع android.jar (اللي بيرمي "not mocked")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")   // (v325) يوتيوب الداخلي — نفس نسخة Flow؛ محتاج jitpack.io في settings
}

// (v398) مسح أي ملف .kt قديم فاضل في الريبو ومش في kt_manifest.txt (قايمة ملفات الإصدار الحالي) قبل الكومبايل —
// بيمنع أخطاء «Unresolved reference» من ملفات اتشالت في إصدار جديد بس ما اتمسحتش من GitHub.
val pruneStaleKt = tasks.register("pruneStaleKt") {
    doLast {
        val mf = file("kt_manifest.txt")
        if (mf.isFile) {
            val want = mf.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val root = file("src/main/java")
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath
                if (rel !in want) { println("pruning stale source: " + rel); f.delete() }
            }
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(pruneStaleKt) }

// (v589 T4) اختبارات المنطق محتاجة مسار assets (prompts/fonts) — بتتقرا من متغير بيئة
tasks.withType(Test::class.java).configureEach {
    environment("SUBTITLER_ASSETS", "$projectDir/src/main/assets")
    testLogging { events("failed"); showStandardStreams = true }
}
