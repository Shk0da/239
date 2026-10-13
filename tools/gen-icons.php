<?php
/**
 * tools/gen-icons.php — генерация иконок APK (PNG через GD).
 *
 *   php tools/gen-icons.php
 *
 * «239» шрифтом Georgia Bold (тот же сериф, что в шапке тренажёра) на
 * «тёплой бумаге» #F5F1E8, чернила #2B2620 и зелёный акцент #52796F
 * (палитра сайта). Без Georgia берётся Arial Bold.
 *
 * Создаёт в android/app/src/main/res/:
 *   mipmap-{mdpi..xxxhdpi}/ic_launcher.png        — legacy, квадрат 48..192
 *   mipmap-{mdpi..xxxhdpi}/ic_launcher_round.png  — legacy, круг
 *   mipmap-{mdpi..xxxhdpi}/ic_launcher_fg.png     — adaptive foreground
 *                                                   (108/162/216/324/432),
 *                                                   «239» в безопасной зоне 66/108
 * Adaptive-обвязка (mipmap-anydpi-v26/*.xml) лежит в репозитории статично.
 */

$root = dirname(__DIR__);
$res  = $root . '/android/app/src/main/res';

const PAPER = [0xF5, 0xF1, 0xE8];   // --bg
const INK   = [0x2B, 0x26, 0x20];   // --ink
const GREEN = [0x52, 0x79, 0x6F];   // --accent

// ---------- Шрифт ----------

function pickFont(): string {
    $candidates = [
        'C:/Windows/Fonts/georgiab.ttf',   // Georgia Bold — сериф шапки сайта
        'C:/Windows/Fonts/arialbd.ttf',
        '/System/Library/Fonts/Supplemental/Georgia Bold.ttf',
        '/System/Library/Fonts/Supplemental/Arial Bold.ttf',
        '/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf',
    ];
    foreach ($candidates as $f) {
        if (is_file($f)) return $f;
    }
    fwrite(STDERR, "gen-icons.php: не найден подходящий TTF-шрифт\n");
    exit(1);
}

// ---------- Рисование ----------

/** Прямоугольник со скруглёнными углами (или круг при $rad = $size/2). */
function rounded(int $size, int $rad): GdImage {
    $im = imagecreatetruecolor($size, $size);
    imagesavealpha($im, true);
    $transparent = imagecolorallocatealpha($im, 0, 0, 0, 127);
    imagefill($im, 0, 0, $transparent);
    $paper = imagecolorallocate($im, ...PAPER);
    imagefilledarc($im, $rad, $rad, $rad * 2, $rad * 2, 0, 360, $paper, IMG_ARC_PIE);
    imagefilledarc($im, $size - $rad, $rad, $rad * 2, $rad * 2, 0, 360, $paper, IMG_ARC_PIE);
    imagefilledarc($im, $rad, $size - $rad, $rad * 2, $rad * 2, 0, 360, $paper, IMG_ARC_PIE);
    imagefilledarc($im, $size - $rad, $size - $rad, $rad * 2, $rad * 2, 0, 360, $paper, IMG_ARC_PIE);
    imagefilledrectangle($im, $rad, 0, $size - $rad, $size, $paper);
    imagefilledrectangle($im, 0, $rad, $size, $size - $rad, $paper);
    return $im;
}

/**
 * «239» по центру. $box — сторона области, в которую вписываем глифы
 * (для legacy это весь холст с полями, для adaptive — безопасная зона).
 * Возвращает смещённый по вертикали холст, чтобы «239» стояло оптически
 * по центру (Georgia цифры чуть оседают вниз).
 */
function draw239(GdImage $im, int $size, int $box, string $font): void {
    $ink = imagecolorallocate($im, ...INK);
    $green = imagecolorallocate($im, ...GREEN);

    // Высота цифр ≈ 36% области, шрифт подбираем по факту
    $target = (int) round($box * 0.36);
    $px = 10;
    do {
        $box239 = imageftbbox($px, 0, $font, '239');
        $h = $box239[1] - $box239[5];
        $px++;
    } while ($h < $target && $px < 400);
    $w = $box239[2] - $box239[0];
    $h = $box239[1] - $box239[5];

    $cx = (int) round(($size - $w) / 2 - $box239[0]);
    // Под цифрами оставляем зелёную черту: цифры чуть выше центра, черта ниже
    $cy = (int) round(($size - $h) / 2 - $box239[5]) + (int) round($box * 0.045);
    imagefttext($im, $px - 1, 0, $cx, $cy, $ink, $font, '239');

    // Черта-акцент под числом (≈ 40% ширины области, толщина ≈ 3.5%)
    $lw = (int) round($box * 0.40);
    $lt = max(2, (int) round($box * 0.035));
    $lx = (int) round(($size - $lw) / 2);
    $ly = (int) round(($size + $h) / 2) + (int) round($box * 0.09);
    imagefilledrectangle($im, $lx, $ly, $lx + $lw, $ly + $lt, $green);
}

function savePng(GdImage $im, string $file): void {
    imagepng($im, $file, 9, PNG_ALL_FILTERS);
    imagedestroy($im);
    echo "  $file\n";
}

// ---------- Погнали ----------

$font = pickFont();
$densities = ['mdpi' => 1, 'hdpi' => 1.5, 'xhdpi' => 2, 'xxhdpi' => 3, 'xxxhdpi' => 4];

foreach ($densities as $d => $k) {
    $dir = "$res/mipmap-$d";
    if (!is_dir($dir)) mkdir($dir, 0777, true);

    // Legacy: полный кадр 48dp (48..192 px), углы ~22% стороны
    $size = (int) round(48 * $k);
    $sq = rounded($size, (int) round($size * 0.22));
    draw239($sq, $size, (int) round($size * 0.86), $font);
    savePng($sq, "$dir/ic_launcher.png");

    $ci = rounded($size, (int) round($size / 2));
    draw239($ci, $size, (int) round($size * 0.82), $font);
    savePng($ci, "$dir/ic_launcher_round.png");

    // Adaptive foreground: холст 108dp, безопасная зона 66dp
    $fgSize = (int) round(108 * $k);
    $fg = imagecreatetruecolor($fgSize, $fgSize);
    imagesavealpha($fg, true);
    $transparent = imagecolorallocatealpha($fg, 0, 0, 0, 127);
    imagefill($fg, 0, 0, $transparent);
    draw239($fg, $fgSize, (int) round(66 * $k), $font);
    savePng($fg, "$dir/ic_launcher_fg.png");
}

echo "OK: иконки сгенерированы\n";
