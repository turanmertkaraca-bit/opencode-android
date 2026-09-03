#!/bin/bash
# p6_build.sh — build v0.6.0-p6 debug APK
set -e
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$HOME/p0-tools/android-sdk"
GRADLE="$HOME/p0-tools/gradle-8.9/bin/gradle"
PROJ=/home/z/my-project/opencode-mobile-p1
cd "$PROJ"
"$GRADLE" assembleDebug --no-daemon 2>&1 | tail -25
echo "=== ARTIFACT ==="
ls -la app/build/outputs/apk/debug/
