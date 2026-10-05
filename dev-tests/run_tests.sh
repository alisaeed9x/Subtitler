#!/usr/bin/env bash
# تشغيل: bash dev-tests/run_tests.sh   (من جذر المشروع)
# بيعمل: (1) compile-check للمشروع كله مقابل android.jar + Media3 stubs  (2) اختبارات JVM للمحرك والصوت وHLS والـ prompts
# محتاج إنترنت لأول مرة بس (بينزل Kotlin compiler و android.jar و org.json من GitHub) — الباقي بيتخزن في $TOOLS
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="${TOOLS:-$HOME/subtitler-tools}"
SRC="$ROOT/app/src/main/java/com/tttt/subtitler"
mkdir -p "$TOOLS" && cd "$TOOLS"

[ -d kotlinc ] || { curl -sL -o k.zip https://github.com/JetBrains/kotlin/releases/download/v1.9.24/kotlin-compiler-1.9.24.zip && unzip -q -o k.zip && rm k.zip; }
[ -f android34.jar ] || curl -sL -o android34.jar https://raw.githubusercontent.com/Reginer/aosp-android-jar/main/android-34/android.jar
if [ ! -d jsonout ]; then
  mkdir -p json jsonout
  for f in JSONObject JSONArray JSONException JSONTokener JSONString JSONPointer JSONPointerException ParserConfiguration JSONParserConfiguration JSONPropertyIgnore JSONPropertyName JSONStringer JSONWriter StringBuilderWriter; do
    curl -sL -o json/$f.java https://raw.githubusercontent.com/stleary/JSON-java/master/src/main/java/org/json/$f.java
  done
  java -m jdk.compiler/com.sun.tools.javac.Main -d jsonout json/*.java
fi
KC="$TOOLS/kotlinc/bin/kotlinc"; AJ="$TOOLS/android34.jar"; STD="$TOOLS/kotlinc/lib/kotlin-stdlib.jar"
OUT="$TOOLS/out"; rm -rf "$OUT" && mkdir -p "$OUT/all" "$OUT/app" "$OUT/t1" "$OUT/t2" "$OUT/t3"

echo "== 1) compile-check للتطبيق كله"
"$KC" -jvm-target 17 -cp "$AJ" -opt-in=kotlin.RequiresOptIn -d "$OUT/all" "$SRC"/*.kt $(find "$ROOT/dev-tests/stubs" -name '*.kt' -o -name '*.java') 2>&1 | grep -E "error" && { echo "FAIL: compile errors"; exit 1; } || echo "OK"

echo "== 2) اختبارات JVM (المنطق بدون Android: Core/Store/AudioCore/Engine)"
NOUI="$SRC/Core.kt $SRC/Store.kt $SRC/AudioCore.kt $SRC/Engine.kt $SRC/Theme.kt $SRC/SubStyle.kt $SRC/PlayerLogic.kt $SRC/Recents.kt $SRC/Models.kt $SRC/Trim.kt $SRC/Blur.kt $SRC/VideoLib.kt $SRC/Stats.kt $SRC/KeyVault.kt $SRC/Speech.kt $SRC/Coverage.kt"
"$KC" -jvm-target 17 -cp "$AJ:$TOOLS/jsonout" -d "$OUT/app" $NOUI 2>&1 | grep error && exit 1 || true
CP="$OUT/app:$TOOLS/jsonout:$STD:$AJ"   # jsonout قبل android.jar عشان org.json الحقيقي يتقدم على الـ stubs
JOPT="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8"
"$KC" -jvm-target 17 -cp "$CP" -d "$OUT/t1" "$ROOT/dev-tests/EngineTest.kt" 2>&1 | grep error && exit 1 || true
"$KC" -jvm-target 17 -cp "$CP" -d "$OUT/t2" "$ROOT/dev-tests/AudioHlsTest.kt" 2>&1 | grep error && exit 1 || true
"$KC" -jvm-target 17 -cp "$CP" -d "$OUT/t3" "$ROOT/dev-tests/PromptTest.kt" 2>&1 | grep error && exit 1 || true
# EngineTest بيقرا الـ assets من المسار ده:
export SUBTITLER_ASSETS="$ROOT/app/src/main/assets"
java $JOPT -cp "$OUT/t1:$CP" EngineTestKt | grep -E "^(PASS|FAIL)|الاختبارات"
java $JOPT -cp "$OUT/t2:$CP" AudioHlsTestKt | grep -E "^(PASS|FAIL)|الاختبارات"
java $JOPT -cp "$OUT/t3:$CP" PromptTestKt
mkdir -p "$OUT/t4"
"$KC" -jvm-target 17 -cp "$CP" -d "$OUT/t4" "$ROOT/dev-tests/SubStyleTest.kt" 2>&1 | grep error && exit 1 || true
java $JOPT -cp "$OUT/t4:$CP" SubStyleTestKt | grep -E "^(PASS|FAIL)|الاختبارات|فشل"
mkdir -p "$OUT/t5"
"$KC" -jvm-target 17 -cp "$CP" -d "$OUT/t5" "$ROOT/dev-tests/PlayerLogicTest.kt" 2>&1 | grep error && exit 1 || true
java $JOPT -cp "$OUT/t5:$CP" PlayerLogicTestKt | grep -E "^(PASS|FAIL)|الاختبارات|فشل"
mkdir -p "$OUT/t6"
"$KC" -jvm-target 17 -cp "$CP" -d "$OUT/t6" "$ROOT/dev-tests/MoreTest.kt" 2>&1 | grep error && exit 1 || true
java $JOPT -cp "$OUT/t6:$CP" MoreTestKt | grep -E "^(PASS|FAIL)|الاختبارات|فشل"
mkdir -p "$OUT/t7"
"$KC" -jvm-target 17 -cp "$CP" -d "$OUT/t7" "$ROOT/dev-tests/PhaseETest.kt" 2>&1 | grep error && exit 1 || true
java $JOPT -cp "$OUT/t7:$CP" PhaseETestKt | grep -E "^(PASS|FAIL)|الاختبارات|فشل"
mkdir -p "$OUT/t8"
"$KC" -jvm-target 17 -cp "$CP" -d "$OUT/t8" "$ROOT/dev-tests/BlurTest.kt" 2>&1 | grep error && exit 1 || true
java $JOPT -cp "$OUT/t8:$CP" BlurTestKt | grep -E "^(PASS|FAIL)|الاختبارات|فشل"
mkdir -p "$OUT/t9"
"$KC" -jvm-target 17 -cp "$CP" -d "$OUT/t9" "$ROOT/dev-tests/LibraryTest.kt" 2>&1 | grep error && exit 1 || true
java $JOPT -cp "$OUT/t9:$CP" LibraryTestKt | grep -E "^(PASS|FAIL)|الاختبارات|فشل"
mkdir -p "$OUT/t10"
"$KC" -jvm-target 17 -cp "$CP" -d "$OUT/t10" "$ROOT/dev-tests/SpeechTest.kt" 2>&1 | grep error && exit 1 || true
java $JOPT -cp "$OUT/t10:$CP" SpeechTestKt | grep -E "^(PASS|FAIL)|اختبارات"
mkdir -p "$OUT/t5"
"$KC" -jvm-target 17 -cp "$CP" -d "$OUT/t5" "$SRC/LockCore.kt" "$ROOT/dev-tests/LockTest.kt" 2>&1 | grep error && exit 1 || true
java $JOPT -cp "$OUT/t5:$CP" LockTestKt | grep -E "^(PASS|FAIL)|الاختبارات"
mkdir -p "$OUT/t10"
"$KC" -jvm-target 17 -cp "$CP" -d "$OUT/t10" "$ROOT/dev-tests/SoundTest.kt" 2>&1 | grep error && exit 1 || true
java $JOPT -cp "$OUT/t10:$CP" SoundTestKt | grep -E "^(PASS|FAIL)|الاختبارات"
mkdir -p "$OUT/t11"
"$KC" -jvm-target 17 -cp "$CP" -d "$OUT/t11" "$ROOT/dev-tests/CoverageTest.kt" 2>&1 | grep error && exit 1 || true
java $JOPT -cp "$OUT/t11:$CP" CoverageTestKt | grep -E "^(PASS|FAIL)|الاختبارات|فشل"
