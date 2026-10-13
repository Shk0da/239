#!/bin/bash
#
# tools/build-www.sh — сборка офлайн-бандла тренажёра в android/assets/www/.
#
#   ./tools/build-www.sh
#
# Что делает:
#   * копирует зеркало сайта (site/: index.html, 239.js, answers.js, img/)
#     в android/assets/www/;
#   * контроль: в www не остаётся ни одного http(s):// URL (кроме
#     SVG-namespace) и все картинки, на которые ссылается 239.js, на месте.
#
# Выход: android/assets/www/ — используется tools/build-apk.sh (-A android/assets).

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SITE="$ROOT/site"
OUT="$ROOT/android/assets/www"

[ -f "$SITE/index.html" ] || { echo "ОШИБКА: нет site/index.html" >&2; exit 1; }
[ -f "$SITE/239.js" ]     || { echo "ОШИБКА: нет site/239.js" >&2; exit 1; }
[ -f "$SITE/answers.js" ] || { echo "ОШИБКА: нет site/answers.js" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT"

cp "$SITE/index.html" "$SITE/239.js" "$SITE/answers.js" "$OUT/"
if [ -d "$SITE/img" ]; then
    mkdir -p "$OUT/img"
    cp "$SITE/img/"*.png "$SITE/img/"*.jpg "$OUT/img/" 2>/dev/null || true
fi

# ---------- Контроль автономности ----------

LEAKS=$(grep -R -I -o 'https\?://[^"'"'"' <>)]*' "$OUT" | grep -v 'www.w3.org' || true)
if [ -n "$LEAKS" ]; then
    echo "ОШИБКА: внешние URL в бандле:" >&2
    echo "$LEAKS" >&2
    exit 1
fi

# Все картинки из 239.js должны лежать в бандле
MISSING=0
while IFS= read -r img; do
    [ -n "$img" ] || continue
    if [ ! -f "$OUT/$img" ]; then
        echo "ОШИБКА: 239.js ссылается на $img, которого нет в site/" >&2
        MISSING=1
    fi
done < <(grep -oE '"img/[^"]*"' "$OUT/239.js" | tr -d '"' | sort -u)
[ "$MISSING" -eq 0 ] || exit 1

echo "OK: $OUT ($(find "$OUT" -type f | wc -l | tr -d ' ') файлов, внешних URL нет)"
