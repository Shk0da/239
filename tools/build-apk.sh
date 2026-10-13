#!/bin/bash
#
# tools/build-apk.sh — сборка автономного APK без Gradle (Windows: git-bash).
#
#   ./tools/build-apk.sh
#
# Пайплайн: aapt2 compile/link → javac → d8 → python-zip → zipalign → apksigner.
# SDK скачивается при первом запуске в tools/android-sdk/ (dl.google.com,
# cmdline-tools для Windows). Кейстор генерится keytool'ом при первом запуске
# (tools/keystore.properties).
#
# Отличия от *nix-варианта (Wallet): d8 и apksigner запускаются через
# java -cp lib/*.jar (без .bat-обёрток), классы дописываются в APK питоном
# (в git-bash нет zip), пути конвертируются cygpath -m.
#
# Выход: dist/t239.apk (+ verify и badging).

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ROOT_M=$(cygpath -m "$ROOT" 2>/dev/null || echo "$ROOT")   # C:/Projects/239 — для java-инструментов
SDK="$ROOT/tools/android-sdk"
BT="$SDK/build-tools/35.0.0"
PLATFORM_JAR="$SDK/platforms/android-34/android.jar"
RES="$ROOT/android/app/src/main/res"
JAVA_SRC="$ROOT/android/app/src/main/java"
MANIFEST="$ROOT/android/app/src/main/AndroidManifest.xml"
ASSETS="$ROOT/android/assets"
DIST="$ROOT/dist"

AAPT2="$BT/aapt2.exe"
ZIPALIGN="$BT/zipalign.exe"
D8JAR="$BT/lib/d8.jar"
APKSIGNER_JAR="$BT/lib/apksigner.jar"

# ---------- 1. SDK (однократно) ----------
# build-tools 35 (не 34): d8 из 34.0.0 (R8 8.2.2-dev) падает NPE на
# class-файлах javac 21 (любой анонимный класс) — в 35.0.0 исправлено.
# Платформа остаётся android-34 (targetSdk).
if [ ! -f "$PLATFORM_JAR" ] || [ ! -f "$AAPT2" ]; then
    echo "==> Качаю Android SDK (build-tools 35.0.0 + platform 34)..."
    CT="$SDK/cmdline-tools/latest"
    if [ ! -f "$CT/bin/sdkmanager.bat" ]; then
        mkdir -p "$SDK/cmdline-tools"
        ZIP="$SDK/cmdline-tools.zip"
        curl -fSL --retry 3 -o "$ZIP" \
            "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
        rm -rf "$CT" "$SDK/cmdline-tools/cmdline-tools"
        unzip -q "$ZIP" -d "$SDK/cmdline-tools"
        mv "$SDK/cmdline-tools/cmdline-tools" "$CT"
        rm -f "$ZIP"
    fi
    (yes || true) | cmd //c "$(cygpath -w "$CT/bin/sdkmanager.bat") --sdk_root=$(cygpath -w "$SDK") --licenses" > /dev/null
    cmd //c "$(cygpath -w "$CT/bin/sdkmanager.bat") --sdk_root=$(cygpath -w "$SDK") build-tools;35.0.0 platforms;android-34" > /dev/null
fi

# ---------- 2. Иконки (однократно) ----------
if ! ls "$RES"/mipmap-xxxhdpi/ic_launcher.png > /dev/null 2>&1; then
    php "$ROOT/tools/gen-icons.php"
fi

# ---------- 3. Офлайн-бандл тренажёра ----------
"$ROOT/tools/build-www.sh"

# ---------- 4. Кейстор (однократно) ----------
KS_FILE="$ROOT/tools/t239.keystore"
KS_PROPS="$ROOT/tools/keystore.properties"
if [ ! -f "$KS_FILE" ]; then
    echo "==> Генерирую подпись (tools/t239.keystore)..."
    KS_PASS="$(openssl rand -hex 16)"
    keytool -genkeypair -keystore "$KS_FILE" -alias t239 -keyalg RSA -keysize 2048 \
        -validity 10950 -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -dname "CN=T239, O=T239" >/dev/null 2>&1
    printf 'store.file=%s\nstore.pass=%s\nalias=t239\n' "$KS_FILE" "$KS_PASS" > "$KS_PROPS"
fi
KS_PASS="$(sed -n 's/^store.pass=//p' "$KS_PROPS")"
KS_ALIAS="$(sed -n 's/^alias=//p' "$KS_PROPS")"

# ---------- 5. Компиляция ----------
WORK="$ROOT/android/build"
rm -rf "$WORK"
mkdir -p "$WORK/gen" "$WORK/classes" "$WORK/dex"

# Версия = число коммитов (монотонно растёт с каждым коммитом):
# versionName 1.0.N. Без git (например, экспорт без .git) — 1(1.0).
VCODE="$(git -C "$ROOT" rev-list --count HEAD 2>/dev/null || echo 0)"
[ "$VCODE" -gt 0 ] || VCODE=1
VNAME="1.0.$((VCODE - 1))"

# Атрибуты в манифесте сильнее флагов aapt2 — патчим копию, не репозиторий
MANIFEST_B="$WORK/AndroidManifest.xml"
sed -e "s|android:versionCode=\"[0-9]*\"|android:versionCode=\"$VCODE\"|" \
    -e "s|android:versionName=\"[^\"]*\"|android:versionName=\"$VNAME\"|" \
    "$MANIFEST" > "$MANIFEST_B"

# Патченная копия res: строка app_version = versionName
RES_B="$WORK/res"
cp -R "$RES" "$RES_B"
sed -i "s|<string name=\"app_version\">[^<]*</string>|<string name=\"app_version\">$VNAME</string>|" \
    "$RES_B/values/strings.xml"

echo "==> aapt2 compile + link (версия $VNAME)"
DEBUG_FLAGS=""
if [ "${T239_DEBUG:-0}" = "1" ]; then
    # debuggable-сборка: включает devtools-сокет WebView (cdp-eval.mjs)
    DEBUG_FLAGS="--debug-mode"
    VNAME="$VNAME-debug"
fi
"$AAPT2" compile --dir "$RES_B" -o "$WORK/res.zip"
"$AAPT2" link $DEBUG_FLAGS -o "$WORK/base.apk" -I "$PLATFORM_JAR" \
    --manifest "$MANIFEST_B" --java "$WORK/gen" -A "$ASSETS" \
    --min-sdk-version 21 --target-sdk-version 34 \
    --auto-add-overlay "$WORK/res.zip"

echo "==> javac"
: > "$WORK/sources.txt"
while IFS= read -r f; do
    cygpath -m "$f" >> "$WORK/sources.txt"
done < <(find "$JAVA_SRC" "$WORK/gen" -name '*.java' | sort)
javac -source 1.8 -target 1.8 -encoding UTF-8 -Xlint:-options \
    -classpath "$PLATFORM_JAR" -d "$WORK/classes" @"$WORK/sources.txt"

echo "==> d8"
: > "$WORK/classes.txt"
while IFS= read -r f; do
    cygpath -m "$f" >> "$WORK/classes.txt"
done < <(find "$WORK/classes" -name '*.class' | sort)
java -cp "$(cygpath -m "$D8JAR")" com.android.tools.r8.D8 \
    --lib "$PLATFORM_JAR" --min-api 21 --output "$WORK/dex" @"$WORK/classes.txt"

# ---------- 6. Сборка и подпись ----------
echo "==> apk + zipalign + apksigner"
cp "$WORK/base.apk" "$WORK/unsigned.apk"

# В git-bash нет zip: дописываем classes.dex в архив питоном (deflate, как zip -q)
python - "$(cygpath -m "$WORK/unsigned.apk")" "$(cygpath -m "$WORK/dex/classes.dex")" <<'PYEOF'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
PYEOF

mkdir -p "$DIST"
"$ZIPALIGN" -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
java -cp "$(cygpath -m "$APKSIGNER_JAR")" com.android.apksigner.ApkSignerTool \
    sign --ks "$KS_FILE" --ks-pass "pass:$KS_PASS" \
    --ks-key-alias "$KS_ALIAS" --key-pass "pass:$KS_PASS" \
    --out "$DIST/t239.apk" "$WORK/aligned.apk"

# ---------- 7. Проверка ----------
java -cp "$(cygpath -m "$APKSIGNER_JAR")" com.android.apksigner.ApkSignerTool \
    verify --print-certs "$DIST/t239.apk" | head -4
echo "---"
"$AAPT2" dump badging "$DIST/t239.apk" | grep -E '^(package|sdkVersion|targetSdkVersion|application-label|uses-permission|launchable)' || true
echo "---"
ls -lh "$DIST/t239.apk" | awk '{print "OK: dist/t239.apk", $5}'
