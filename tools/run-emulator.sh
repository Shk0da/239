#!/bin/bash
#
# tools/run-emulator.sh — запуск «Тренажёра 239» в Android-эмуляторе (Windows).
#
#   ./tools/run-emulator.sh              # headless: ставит APK, открывает,
#                                        # ждёт Ctrl+C (лог в logcat)
#   ./tools/run-emulator.sh --screenshot # boot → установка → скриншот → выход
#   ./tools/run-emulator.sh --stop       # остановить эмулятор
#
# Сам ставит недостающие компоненты SDK (emulator, platform-tools,
# system-images;android-34;google_apis;x86_64), создаёт AVD в
# tools/android-avd (не в ~/.android), ждёт загрузки, устанавливает
# dist/t239.apk и открывает приложение.
#
# Требует Windows Hypervisor Platform (WHPX) — без неё x86_64-образ
# не грузится; проверка: `emulator -accel-check`.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="$ROOT/tools/android-sdk"
AVDHOME="$ROOT/tools/android-avd"
EMU="$SDK/emulator/emulator.exe"
ADB="$SDK/platform-tools/adb.exe"
AVD_NAME="t239"
PKG="com.ianshkondin.mat239"

MODE="run"
[ "${1:-}" = "--screenshot" ] && MODE="screenshot"
[ "${1:-}" = "--stop" ] && MODE="stop"

if [ "$MODE" = "stop" ]; then
    "$ADB" -s emulator-5554 emu kill > /dev/null 2>&1 || true
    echo "OK: эмулятор остановлен (если был)"
    exit 0
fi

# ---------- 1. Компоненты SDK (однократно) ----------
CT="$SDK/cmdline-tools/latest/bin/sdkmanager.bat"
need_install=0
[ -f "$EMU" ] || need_install=1
[ -f "$ADB" ] || need_install=1
[ -d "$SDK/system-images/android-34/google_apis/x86_64" ] || need_install=1
if [ "$need_install" = 1 ]; then
    echo "==> Качаю emulator + platform-tools + system image (API 34, x86_64)..."
    cmd //c "$(cygpath -w "$CT") --sdk_root=$(cygpath -w "$SDK") platform-tools emulator system-images;android-34;google_apis;x86_64" > /dev/null
fi

# ---------- 2. AVD (однократно) ----------
export ANDROID_AVD_HOME="$(cygpath -m "$AVDHOME")"
export ANDROID_SDK_ROOT="$(cygpath -m "$SDK")"
mkdir -p "$AVDHOME"
CT_LIB="$(cygpath -m "$SDK/cmdline-tools/latest/lib/avdmanager-classpath.jar")"
if [ ! -d "$AVDHOME/$AVD_NAME.avd" ]; then
    echo "==> Создаю AVD $AVD_NAME (API 34)..."
    # avdmanager через java напрямую: cmd //c ломает кавычки вокруг
    # package-пути с точками с запятой
    echo no | java -cp "$CT_LIB" com.android.sdklib.tool.AvdManagerCli create avd \
        -n "$AVD_NAME" -k "system-images;android-34;google_apis;x86_64" -d pixel_5 > /dev/null
fi

# ---------- 3. Запуск ----------
echo "==> Запускаю эмулятор (headless)..."
"$EMU" -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim \
    -gpu swiftshader_indirect -no-snapshot > /tmp/t239_emulator.log 2>&1 &
EMU_PID=$!
trap '[ "$MODE" = screenshot ] || kill $EMU_PID 2>/dev/null || true' EXIT

"$ADB" wait-for-device
echo "==> Жду полной загрузки (sys.boot_completed)..."
for i in $(seq 1 180); do
    BOOT=$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
    [ "$BOOT" = "1" ] && break
    sleep 2
done
if [ "${BOOT:-}" != "1" ]; then
    echo "ОШИБКА: эмулятор не загрузился за 6 минут — смотри /tmp/t239_emulator.log" >&2
    [ "$MODE" = "screenshot" ] && "$ADB" -s emulator-5554 emu kill > /dev/null 2>&1 || true
    exit 1
fi
echo "==> Загрузился. Ставлю dist/t239.apk..."
"$ADB" install -r "$ROOT/dist/t239.apk"

# Полностью офлайн-проверка: airplane mode включён ДО старта приложения
"$ADB" shell svc wifi disable > /dev/null 2>&1 || true
"$ADB" shell svc data disable > /dev/null 2>&1 || true

echo "==> Открываю приложение..."
"$ADB" logcat -c
"$ADB" shell am start -n "$PKG/.MainActivity" > /dev/null
sleep 8

CRASH=$("$ADB" logcat -d 2>/dev/null | grep -E "FATAL EXCEPTION|AndroidRuntime.*Process: $PKG" || true)
if [ -n "$CRASH" ]; then
    echo "ОШИБКА: приложение упало:" >&2
    echo "$CRASH" >&2
    "$ADB" logcat -d > /tmp/t239_crash.log 2>&1 || true
    exit 1
fi
echo "OK: приложение запущено, крэшей нет"

if [ "$MODE" = "screenshot" ]; then
    mkdir -p "$ROOT/dist"
    "$ADB" shell screencap -p /sdcard/t239.png
    "$ADB" pull /sdcard/t239.png "$ROOT/dist/screen.png" > /dev/null
    "$ADB" shell rm /sdcard/t239.png
    "$ADB" -s emulator-5554 emu kill > /dev/null 2>&1 || true
    echo "OK: скриншот в dist/screen.png"
else
    echo "== Лог (Ctrl+C — выход) =="
    "$ADB" logcat
fi
