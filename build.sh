#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
GRADLE_VERSION="9.7.0"
CACHE_DIR="$PROJECT_DIR/.gradle-dist"
GRADLE_HOME="$CACHE_DIR/gradle-$GRADLE_VERSION"
ZIP="$CACHE_DIR/gradle-$GRADLE_VERSION-bin.zip"
mkdir -p "$CACHE_DIR"
if [[ ! -x "$GRADLE_HOME/bin/gradle" ]]; then
  if [[ ! -f "$ZIP" ]]; then
    echo "Downloading Gradle $GRADLE_VERSION..."
    curl -fL --retry 3 "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ZIP"
  fi
  rm -rf "$GRADLE_HOME"
  unzip -q "$ZIP" -d "$CACHE_DIR"
fi
export GRADLE_USER_HOME="$PROJECT_DIR/.gradle-user-home"

# Compose / AndroidX used by 1.3.0 requires compileSdk 37.
# Install the platform here as a fallback so CI does not depend on an older
# workflow step that may still only preinstall android-35.
if command -v sdkmanager >/dev/null 2>&1; then
  echo "Ensuring Android SDK Platform 37 is installed..."
  yes | sdkmanager --licenses >/dev/null 2>&1 || true
  sdkmanager "platforms;android-37" >/dev/null
fi
"$GRADLE_HOME/bin/gradle" --no-daemon :app:assembleRelease
mkdir -p "$PROJECT_DIR/dist"
APK="$(find "$PROJECT_DIR/app/build/outputs/apk/release" -maxdepth 1 -type f -name '*.apk' | head -n 1)"
test -n "$APK"
cp "$APK" "$PROJECT_DIR/dist/HyperBackground-v1.3.2-test.apk"
echo "Built: $PROJECT_DIR/dist/HyperBackground-v1.3.2-test.apk"
