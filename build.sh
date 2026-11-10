#!/data/data/com.termux/files/usr/bin/bash
#
# build.sh - Bu depoyu (linux-tm) Termux içinde derler ve istenirse APK'yı kurar.
#
# Kullanım:
#   bash build.sh              # debug APK derler (tüm mimariler)
#   bash build.sh --install    # derler ve APK'yı termux-open ile kurar
#   bash build.sh --clean      # önce temizler, sonra derler
#   bash build.sh --variant apt-android-5   # farklı bootstrap sürümü ile derler
#   bash build.sh --help
#
# Çıktı:
#   app/build/outputs/apk/debug/termux-app_*_universal.apk
#   app/build/outputs/apk/debug/termux-app_*_arm64-v8a.apk (ve diğer mimariler)
#
set -u

C_RESET=""; C_BOLD=""; C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""; C_CYAN=""
if [ -t 1 ] && command -v tput >/dev/null 2>&1 && [ "$(tput colors 2>/dev/null || echo 0)" -ge 8 ]; then
    C_RESET="$(tput sgr0)"; C_BOLD="$(tput bold)"; C_RED="$(tput setaf 1)"
    C_GREEN="$(tput setaf 2)"; C_YELLOW="$(tput setaf 3)"; C_BLUE="$(tput setaf 4)"
    C_CYAN="$(tput setaf 6)"
fi

info()  { printf '%s[bilgi]%s %s\n' "$C_BLUE" "$C_RESET" "$*"; }
ok()    { printf '%s[tamam]%s %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn()  { printf '%s[uyarı]%s %s\n' "$C_YELLOW" "$C_RESET" "$*"; }
die()   { printf '%s[hata]%s %s\n' "$C_RED" "$C_RESET" "$*" >&2; exit 1; }

REPO_DIR="$(cd "$(dirname "$0")" && pwd)"
APK_DIR="$REPO_DIR/app/build/outputs/apk/debug"

DO_INSTALL=0
DO_CLEAN=0
PACKAGE_VARIANT="${TERMUX_PACKAGE_VARIANT:-apt-android-7}"

while [ $# -gt 0 ]; do
    case "$1" in
        --install|-i) DO_INSTALL=1 ;;
        --clean|-c) DO_CLEAN=1 ;;
        --variant)
            shift
            [ $# -gt 0 ] || die "--variant için bir değer gerekli (apt-android-7 / apt-android-5)"
            PACKAGE_VARIANT="$1"
            ;;
        --help|-h)
            sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *) die "Bilinmeyen seçenek: $1 (--help ile yardımı görebilirsin)" ;;
    esac
    shift
done

[ -f "$REPO_DIR/gradlew" ] || die "$REPO_DIR/gradlew bulunamadı, bu betiği depo kökünden çalıştır."
command -v java >/dev/null 2>&1 || die "java bulunamadı, önce kur: pkg install openjdk-17"

info "Depo       : $REPO_DIR"
info "Sürüm      : $PACKAGE_VARIANT"
info "Java       : $(java -version 2>&1 | head -n 1)"

chmod +x "$REPO_DIR/gradlew"

cd "$REPO_DIR" || die "Depo dizinine girilemedi: $REPO_DIR"

if [ "$DO_CLEAN" -eq 1 ]; then
    info "Temizleniyor (./gradlew clean)..."
    ./gradlew clean || warn "clean başarısız oldu, devam ediliyor."
fi

info "Derleniyor (./gradlew assembleDebug), bu işlem 5-20 dakika sürebilir..."
TERMUX_PACKAGE_VARIANT="$PACKAGE_VARIANT" ./gradlew assembleDebug || die "Derleme başarısız oldu."

ok "Derleme tamamlandı. APK dosyaları:"
ls -1 "$APK_DIR"/*.apk 2>/dev/null || die "APK bulunamadı: $APK_DIR"

if [ "$DO_INSTALL" -eq 1 ]; then
    APK=""
    # Cihazın mimarisine uygun APK'yı seç, yoksa universal APK kullan
    case "$(uname -m)" in
        aarch64|arm64) APK="$(ls "$APK_DIR"/*arm64-v8a*.apk 2>/dev/null | head -n 1)" ;;
        armv7*|armv8l) APK="$(ls "$APK_DIR"/*armeabi-v7a*.apk 2>/dev/null | head -n 1)" ;;
        x86_64) APK="$(ls "$APK_DIR"/*x86_64*.apk 2>/dev/null | head -n 1)" ;;
        i686|i386) APK="$(ls "$APK_DIR"/*_x86*.apk 2>/dev/null | head -n 1)" ;;
    esac
    [ -n "$APK" ] || APK="$(ls "$APK_DIR"/*universal*.apk 2>/dev/null | head -n 1)"
    [ -n "$APK" ] || die "Kurulacak APK bulunamadı."

    info "Kuruluyor: $APK"
    if command -v termux-open >/dev/null 2>&1; then
        termux-open "$APK"
        ok "Kurulum ekranı açıldı, 'Kur' düğmesine bas."
    else
        warn "termux-open bulunamadı, APK'yı elle kur: $APK"
    fi
else
    info "Kurmak için: bash build.sh --install"
fi
