#!/data/data/com.termux/files/usr/bin/bash
#
# kur.sh - Termux (linux-tm) ve bu depodaki özel komutlar için kurulum betiği.
#
# Bu dosya depodaki "kur.s" betiğidir. Termux içinde çalıştırıldığında eksik olan
# paketleri kurar (depo, aarch64/arm/i686/x86_64 mimarisine göre), ardından bu depoyu
# klonlar ve uygulamayı derler. Son olarak derlemenin nasıl yapılacağını, APK'nın nerede
# olduğunu ve kurma komutunu ekrana yazar.
#
# Kullanım:
#   bash kur.sh              # paketleri kur, depoyu klonla ve APK'yı derle
#   bash kur.sh --no-build   # sadece paketleri kur ve depoyu klonla
#   bash kur.sh --deps-only   # sadece eksik paketleri kur
#   bash kur.sh --help       # yardımı göster
#
# Notlar:
#   * Betik Termux dışında (normal bir Linux makinesinde) çalıştırılırsa paket kurulumunu
#     atlar ve sadece derleme adımlarını gösterir/yapar.
#   * Derleme için Android SDK (build-tools, platform-tools, platform android-36) ve
#     Java 17 gerekir. Bunlar aşağıdaki paketlerle kurulur.
#
set -u

# ---------------------------------------------------------------------------
# Renkler (desteklenmiyorsa boş kalır)
# ---------------------------------------------------------------------------
if [ -t 1 ] && command -v tput >/dev/null 2>&1 && [ "$(tput colors 2>/dev/null || echo 0)" -ge 8 ]; then
    C_RESET="$(tput sgr0)"
    C_BOLD="$(tput bold)"
    C_RED="$(tput setaf 1)"
    C_GREEN="$(tput setaf 2)"
    C_YELLOW="$(tput setaf 3)"
    C_BLUE="$(tput setaf 4)"
    C_CYAN="$(tput setaf 6)"
else
    C_RESET=""; C_BOLD=""; C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""; C_CYAN=""
fi

info()  { printf '%s[bilgi]%s %s\n' "$C_BLUE" "$C_RESET" "$*"; }
ok()    { printf '%s[tamam]%s %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn()  { printf '%s[uyarı]%s %s\n' "$C_YELLOW" "$C_RESET" "$*"; }
error() { printf '%s[hata]%s %s\n' "$C_RED" "$C_RESET" "$*" >&2; }

die() {
    error "$*"
    exit 1
}

# ---------------------------------------------------------------------------
# Sabitler
# ---------------------------------------------------------------------------
REPO_URL_DEFAULT="https://github.com/Mio-129/linux-tm.git"
REPO_BRANCH_DEFAULT="master"
INSTALL_DIR_DEFAULT="$HOME/linux-tm"
BUILD_SCRIPT="$INSTALL_DIR_DEFAULT/build.sh"

PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
IS_TERMUX=0
if [ -d "$PREFIX_DIR" ] && [ "${PREFIX_DIR#/data/data/com.termux}" != "$PREFIX_DIR" ]; then
    IS_TERMUX=1
fi

# Bu betiğin bulunduğu dizin, betik deponun içinden çalıştırıldıysa depo köküdür
SCRIPT_DIR="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
if [ -d "$SCRIPT_DIR/.git" ] && [ -f "$SCRIPT_DIR/gradlew" ]; then
    INSTALL_DIR_DEFAULT="$SCRIPT_DIR"
    BUILD_SCRIPT="$INSTALL_DIR_DEFAULT/build.sh"
fi

# ---------------------------------------------------------------------------
# Yardım
# ---------------------------------------------------------------------------
show_help() {
    cat <<EOF
${C_BOLD}kur.sh${C_RESET} - Termux / linux-tm kurulum betiği

Kullanım:
  bash kur.sh [seçenekler]

Seçenekler:
  --deps-only     Sadece eksik paketleri kur, depoyu klonlama ve derleme yapma
  --no-build      Paketleri kur ve depoyu klonla, ama derleme yapma
  --dir <yol>     Deponun klonlanacağı dizin (varsayılan: $INSTALL_DIR_DEFAULT)
  --repo <url>    Klonlanacak depo (varsayılan: $REPO_URL_DEFAULT)
  --help, -h      Bu yardımı göster

Ne kurar:
  * git, openssl, curl, unzip, tar, coreutils
  * openjdk-17 (Java 17, Gradle için gerekli)
  * Android SDK: platform-tools, build-tools, platform android-36, NDK
  * gradle (Gradle wrapper çalışmazsa yedek olarak)

Derleme:
  bash build.sh            # tüm mimariler için debug APK derler
  bash build.sh --install  # derler ve APK'yı cihaza kurar (termux-open ile)

Çıktı:
  app/build/outputs/apk/debug/*.apk

Özel komutlar (derlemeden sonra Termux içinde):
  swset <dosya>   Fotoğraf veya videoyu terminalin arkasına koyar (yazılar üstte kalır)
  swset           Klasördeki ilk fotoğraf/videoyu arka plana koyar
  swset off       Arka planı temizler
  so              Arka plandaki videonun sesini açar
  so off          Sesi kapatır
  so stop         Sesi ve arka planı durdurur
EOF
}

# ---------------------------------------------------------------------------
# Argümanlar
# ---------------------------------------------------------------------------
DEPS_ONLY=0
NO_BUILD=0
INSTALL_DIR="$INSTALL_DIR_DEFAULT"
REPO_URL="$REPO_URL_DEFAULT"

while [ $# -gt 0 ]; do
    case "$1" in
        --deps-only) DEPS_ONLY=1 ;;
        --no-build) NO_BUILD=1 ;;
        --dir)
            shift
            [ $# -gt 0 ] || die "--dir için bir yol gerekli"
            INSTALL_DIR="$1"
            BUILD_SCRIPT="$INSTALL_DIR/build.sh"
            ;;
        --repo)
            shift
            [ $# -gt 0 ] || die "--repo için bir url gerekli"
            REPO_URL="$1"
            ;;
        --help|-h) show_help; exit 0 ;;
        *) die "Bilinmeyen seçenek: $1 (--help ile yardımı görebilirsin)" ;;
    esac
    shift
done

# ---------------------------------------------------------------------------
# Paket yönetimi
# ---------------------------------------------------------------------------
ARCH="$(uname -m 2>/dev/null || echo unknown)"

check_pkg_manager() {
    if [ "$IS_TERMUX" -eq 1 ]; then
        command -v pkg >/dev/null 2>&1 || die "Termux 'pkg' komutu bulunamadı, Termux içinde çalıştırıldığından emin ol."
    else
        if command -v apt-get >/dev/null 2>&1; then
            :
        elif command -v dnf >/dev/null 2>&1; then
            :
        elif command -v pacman >/dev/null 2>&1; then
            :
        else
            warn "Desteklenen bir paket yöneticisi bulunamadı, paket kurulumu atlanıyor."
            return 1
        fi
    fi
    return 0
}

install_packages() {
    check_pkg_manager || return 0

    info "Mimari: $ARCH"
    info "Eksik paketler kuruluyor ve depo güncelleniyor, bu işlem biraz sürebilir..."

    if [ "$IS_TERMUX" -eq 1 ]; then
        # Termux paketleri: temel araçlar + Java 17 + Android SDK
        pkg update -y || warn "pkg update başarısız oldu, devam ediliyor."
        # shellcheck disable=SC2086
        pkg install -y git openssl curl unzip tar coreutils openjdk-17 \
            || warn "Bazı paketler kurulamadı, eksik olanları sonra 'pkg install <paket>' ile kurabilirsin."
        # Android SDK araçları (Termux'ta ayrı paketlerdir)
        # shellcheck disable=SC2086
        pkg install -y android-sdk platform-tools aapt apksigner gradle ndk-sysroot \
            || warn "Android SDK paketlerinin bir kısmı kurulamadı."
    else
        local SUDO=""
        if [ "$(id -u)" -ne 0 ]; then
            if command -v sudo >/dev/null 2>&1; then
                SUDO="sudo"
            else
                warn "root veya sudo olmadan paket kurulumu yapılamaz, atlanıyor."
                return 0
            fi
        fi

        if command -v apt-get >/dev/null 2>&1; then
            $SUDO apt-get update -y || warn "apt-get update başarısız oldu."
            # shellcheck disable=SC2086
            $SUDO apt-get install -y git curl unzip tar coreutils openjdk-17-jdk-headless \
                || warn "Bazı paketler kurulamadı."
        elif command -v dnf >/dev/null 2>&1; then
            $SUDO dnf install -y git curl unzip tar coreutils java-17-openjdk-headless \
                || warn "Bazı paketler kurulamadı."
        elif command -v pacman >/dev/null 2>&1; then
            $SUDO pacman -Sy --noconfirm git curl unzip tar coreutils jdk17-openjdk \
                || warn "Bazı paketler kurulamadı."
        fi
    fi

    ok "Paket kurulumu tamamlandı."
}

# ---------------------------------------------------------------------------
# Depoyu alma
# ---------------------------------------------------------------------------
clone_repo() {
    if [ -d "$INSTALL_DIR/.git" ]; then
        info "Depo zaten var: $INSTALL_DIR (güncelleniyor)"
        ( cd "$INSTALL_DIR" && git pull --ff-only ) || warn "git pull yapılamadı, mevcut kopya kullanılacak."
        return 0
    fi

    if [ -d "$INSTALL_DIR" ] && [ -n "$(ls -A "$INSTALL_DIR" 2>/dev/null)" ]; then
        die "$INSTALL_DIR dizini dolu ve bir git deposu değil. --dir <baska/dizin> ile başka bir yer seç."
    fi

    command -v git >/dev/null 2>&1 || die "git bulunamadı, önce kur: pkg install git"
    info "Depo klonlanıyor: $REPO_URL -> $INSTALL_DIR"
    git clone --branch "$REPO_BRANCH_DEFAULT" "$REPO_URL" "$INSTALL_DIR" \
        || die "Depo klonlanamadı. İnternet bağlantını kontrol et."
    ok "Depo klonlandı."
}

# ---------------------------------------------------------------------------
# Derleme
# ---------------------------------------------------------------------------
show_build_instructions() {
    echo
    printf '%s%sDerleme%s\n' "$C_BOLD" "$C_CYAN" "$C_RESET"
    echo "  Depo dizini : $INSTALL_DIR"
    echo "  Derleme     : cd $INSTALL_DIR && bash build.sh"
    echo "  APK çıktısı : $INSTALL_DIR/app/build/outputs/apk/debug/"
    echo "  Kurulum     : bash $INSTALL_DIR/build.sh --install"
    echo
    echo "  Derleme adımları (build.sh ile aynı):"
    echo "    cd $INSTALL_DIR"
    echo "    export TERMUX_PACKAGE_VARIANT=apt-android-7"
    echo "    ./gradlew assembleDebug"
    echo
    echo "  Tek seferde:"
    echo "    cd $INSTALL_DIR && bash build.sh --install"
    echo
}

run_build() {
    [ "$DEPS_ONLY" -eq 0 ] || return 0
    clone_repo

    if [ "$NO_BUILD" -eq 1 ]; then
        show_build_instructions
        ok "Kurulum tamamlandı (derleme atlandı)."
        return 0
    fi

    if [ ! -f "$INSTALL_DIR/gradlew" ]; then
        die "$INSTALL_DIR/gradlew bulunamadı, depo eksik görünüyor."
    fi

    if ! command -v java >/dev/null 2>&1; then
        warn "java bulunamadı, derleme yapılamaz. Kurmak için: pkg install openjdk-17"
        show_build_instructions
        return 1
    fi

    info "APK derleniyor (assembleDebug), bu işlem 5-20 dakika sürebilir..."
    ( cd "$INSTALL_DIR" \
        && chmod +x ./gradlew \
        && TERMUX_PACKAGE_VARIANT="${TERMUX_PACKAGE_VARIANT:-apt-android-7}" ./gradlew assembleDebug ) \
        || {
            error "Derleme başarısız oldu."
            show_build_instructions
            return 1
        }

    ok "Derleme tamamlandı."
    ls -1 "$INSTALL_DIR"/app/build/outputs/apk/debug/*.apk 2>/dev/null || true
    show_build_instructions
}

# ---------------------------------------------------------------------------
# Ana akış
# ---------------------------------------------------------------------------
main() {
    printf '%s%sTermux / linux-tm kurulum betiği%s\n' "$C_BOLD" "$C_CYAN" "$C_RESET"
    info "PREFIX: $PREFIX_DIR (Termux: $([ "$IS_TERMUX" -eq 1 ] && echo evet || echo hayır))"
    info "Betik dizini: ${SCRIPT_DIR:-?}"

    if [ "$IS_TERMUX" -eq 0 ]; then
        warn "Termux ortamı algılanmadı, paket kurulumu Termux'a özel adımları atlayacak."
    fi

    install_packages
    run_build
    echo
    ok "Hazır. Özel komutlar için --help bölümüne bak: bash kur.sh --help"
}

main "$@"
