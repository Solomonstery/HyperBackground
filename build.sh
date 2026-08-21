#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$PROJECT_DIR/../tools/android-sdk}}"
ANDROID_JAR="$ANDROID_SDK_DIR/platforms/android-35/android.jar"
BUILD_TOOLS="$ANDROID_SDK_DIR/build-tools/35.0.1"
JAVA_HOME_DIR="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export PATH="$JAVA_HOME_DIR/bin:$PATH"
[[ -f "$ANDROID_JAR" ]] || { echo "缺少 Android SDK Platform 35：$ANDROID_JAR" >&2; exit 1; }
[[ -x "$BUILD_TOOLS/aapt2" && -x "$BUILD_TOOLS/d8" && -x "$BUILD_TOOLS/apksigner" ]] || { echo "缺少 Android Build Tools 35.0.1：$BUILD_TOOLS" >&2; exit 1; }
BUILD_DIR="$PROJECT_DIR/build"; DIST_DIR="$PROJECT_DIR/dist"; SIGNING_DIR="$PROJECT_DIR/signing"
rm -rf "$BUILD_DIR"; mkdir -p "$BUILD_DIR/compiled" "$BUILD_DIR/generated" "$BUILD_DIR/stubs" "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$DIST_DIR" "$SIGNING_DIR"
"$BUILD_TOOLS/aapt2" compile --dir "$PROJECT_DIR/app/src/main/res" -o "$BUILD_DIR/compiled/resources.zip"
"$BUILD_TOOLS/aapt2" link -I "$ANDROID_JAR" --manifest "$PROJECT_DIR/app/src/main/AndroidManifest.xml" -A "$PROJECT_DIR/app/src/main/assets" --java "$BUILD_DIR/generated" --min-sdk-version 28 --target-sdk-version 35 --version-code 2 --version-name 1.1.0 -o "$BUILD_DIR/unsigned.apk" "$BUILD_DIR/compiled/resources.zip"
mapfile -t STUB_SOURCES < <(find "$PROJECT_DIR/compile-stubs" -name '*.java' -print | sort)
"$JAVA_HOME_DIR/bin/javac" -source 8 -target 8 -proc:none -Xlint:none -classpath "$ANDROID_JAR" -d "$BUILD_DIR/stubs" "${STUB_SOURCES[@]}"
mapfile -t APP_SOURCES < <(find "$PROJECT_DIR/app/src/main/java" "$BUILD_DIR/generated" -name '*.java' -print | sort)
"$JAVA_HOME_DIR/bin/javac" -source 8 -target 8 -proc:none -Xlint:none -classpath "$ANDROID_JAR:$BUILD_DIR/stubs" -d "$BUILD_DIR/classes" "${APP_SOURCES[@]}"
(cd "$BUILD_DIR/classes" && zip -qr "$BUILD_DIR/app-classes.jar" .)
"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --min-api 28 --output "$BUILD_DIR/dex" "$BUILD_DIR/app-classes.jar"
(cd "$BUILD_DIR/dex" && zip -q -u "$BUILD_DIR/unsigned.apk" classes.dex)
(cd "$PROJECT_DIR/module-meta" && zip -q -u "$BUILD_DIR/unsigned.apk" META-INF/xposed/scope.list)
"$BUILD_TOOLS/zipalign" -f 4 "$BUILD_DIR/unsigned.apk" "$BUILD_DIR/aligned.apk"
KEYSTORE="$SIGNING_DIR/hyperbackground-build.jks"
if [[ ! -f "$KEYSTORE" ]]; then "$JAVA_HOME_DIR/bin/keytool" -genkeypair -keystore "$KEYSTORE" -storepass hyperbackground -keypass hyperbackground -alias hyperbackground -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=HyperBackground,O=Cangcu,C=CN" >/dev/null 2>&1; fi
OUTPUT="$DIST_DIR/HyperBackground-v1.1.0.apk"
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias hyperbackground --ks-pass pass:hyperbackground --key-pass pass:hyperbackground --out "$OUTPUT" "$BUILD_DIR/aligned.apk"
"$BUILD_TOOLS/apksigner" verify --verbose "$OUTPUT"
"$BUILD_TOOLS/aapt2" dump badging "$OUTPUT" | sed -n '1,8p'
echo "构建完成：$OUTPUT"
