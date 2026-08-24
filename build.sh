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
    if ! sdkmanager "platforms;android-37.0" "build-tools;36.0.0" >/dev/null; then
        sdkmanager "platforms;android-37" "build-tools;36.0.0" >/dev/null
    fi
fi
"$GRADLE_HOME/bin/gradle" --no-daemon :app:assembleRelease
mkdir -p "$PROJECT_DIR/dist"
APK="$(find "$PROJECT_DIR/app/build/outputs/apk/release" -maxdepth 1 -type f -name '*.apk' | head -n 1)"
test -n "$APK"
VERSION="$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)"/\1/p' "$PROJECT_DIR/app/build.gradle.kts" | head -n 1)"
test -n "$VERSION"
TARGET="$PROJECT_DIR/dist/HyperBackground-v$VERSION.apk"
cp "$APK" "$TARGET"
echo "Built: $TARGET"
