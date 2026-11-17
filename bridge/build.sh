#!/bin/sh
# Сборка ClusterBridge без Gradle: aapt2 → javac → d8 → zip → zipalign → apksigner.
# Нужны JDK 17 и Android SDK (build-tools, platform). Пути переопределяются переменными окружения:
#   JAVA_HOME  — JDK 17 (по умолчанию из Homebrew)
#   SDK        — Android SDK (по умолчанию ~/Library/Android/sdk)
#   BT_VER     — версия build-tools (по умолчанию 36.0.0)
#   PLATFORM   — платформа android-XX (по умолчанию android-36)
#   KS, KS_PASS — ключ подписи и его пароль; если файла KS нет, создаётся новый ключ (не коммитить!)
set -e
cd "$(dirname "$0")"
JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
SDK=${SDK:-$HOME/Library/Android/sdk}
BT=$SDK/build-tools/${BT_VER:-36.0.0}
PL=$SDK/platforms/${PLATFORM:-android-36}/android.jar
KS=${KS:-release.ks}
KS_PASS=${KS_PASS:-changeit}

if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias clusterbridge \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ClusterBridge"
  echo "создан новый ключ $KS — сохраните его: обновить установленный мост можно только тем же ключом"
fi

rm -rf out && mkdir -p out/classes
"$BT/aapt2" compile --dir res -o out/res.zip
"$BT/aapt2" link -o out/base.apk --manifest AndroidManifest.xml -I "$PL" out/res.zip
javac -Xlint:-options -source 8 -target 8 -bootclasspath "$PL" -d out/classes $(find src -name "*.java")
"$BT/d8" --min-api 24 --lib "$PL" --output out $(find out/classes -name "*.class")
cp out/base.apk out/app.apk
(cd out && zip -qj app.apk classes.dex)
"$BT/zipalign" -f 4 out/app.apk out/app-aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --out out/ClusterBridge.apk out/app-aligned.apk
echo "собрано: bridge/out/ClusterBridge.apk"
