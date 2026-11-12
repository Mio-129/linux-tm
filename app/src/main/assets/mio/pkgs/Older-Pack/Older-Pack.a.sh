#!/data/data/com.termux/files/usr/bin/sh
#
# Older-Pack — birinci aşama betiği (.a.sh)
#
# Bu betik "Mio menü > Paketler > Older-Pack > Kur" ile (veya `mio paket kur Older-Pack`)
# İLK çalışan betiktir: asıl kurulum burada yapılır.
#
# Kurallar:
#   * Çıkış kodu 0 değilse ikinci aşama (Older-Tool.b.sh) HİÇ çalışmaz.
#   * Bu yüzden her kritik komuttan sonra `|| exit 1` var.
#   * Betik tekrar çalıştırılabilir olmalı, yani "zaten kurulu" durumunda da hata vermemeli.
#
# Kendi paketini yazarken sadece PAKETLER= satırını ve altındaki ek adımları değiştir.

set -u

AD="Older-Pack"
say() { printf '[%s] %s\n' "$AD" "$*"; }

# Kurulacak paketler (boşlukla ayrılmış). İhtiyacına göre değiştir.
PAKETLER="git curl wget nano less tar unzip zip python"

# ------------------------------------------------------------------------------------------
# 0) Ortam kontrolü
# ------------------------------------------------------------------------------------------
if [ -z "${PREFIX:-}" ]; then
    PREFIX=/data/data/com.termux/files/usr
fi

if [ ! -x "$PREFIX/bin/pkg" ]; then
    say "HATA: $PREFIX/bin/pkg bulunamadı (Termux ön yüklemesi eksik)."
    exit 1
fi

say "birinci aşama başladı ($(date 2>/dev/null))"
say "PREFIX: $PREFIX"

# ------------------------------------------------------------------------------------------
# 1) Depo listesini güncelle
# ------------------------------------------------------------------------------------------
say "depo listesi güncelleniyor (pkg update)"
if ! pkg update -y >/tmp/older-pack-update.log 2>&1; then
    say "UYARI: pkg update hata verdi, ayrıntı: /tmp/older-pack-update.log"
    # Depo hatası paket kurulumunu her zaman engellemez, bu yüzden burada durmuyoruz.
fi

# ------------------------------------------------------------------------------------------
# 2) Temel paketleri kur
# ------------------------------------------------------------------------------------------
say "kurulacak: $PAKETLER"
# shellcheck disable=SC2086
if ! pkg install -y $PAKETLER 2>&1 | tail -n 15; then
    say "HATA: paketler kurulamadı."
    exit 1
fi

# Kurulum gerçekten oldu mu, tek tek doğrula
EKSIK=""
for p in $PAKETLER; do
    if ! dpkg -s "$p" >/dev/null 2>&1; then
        EKSIK="$EKSIK $p"
    fi
done

if [ -n "$EKSIK" ]; then
    say "HATA: şu paketler kurulamadı:$EKSIK"
    exit 1
fi
say "temel paketler tamam"

# ------------------------------------------------------------------------------------------
# 3) Klasörler
# ------------------------------------------------------------------------------------------
mkdir -p "$HOME/projects" "$HOME/.local/bin" || {
    say "HATA: klasörler oluşturulamadı."
    exit 1
}
say "klasörler hazır: ~/projects, ~/.local/bin"

# ------------------------------------------------------------------------------------------
# 4) Storage erişimi (izin verildiyse)
# ------------------------------------------------------------------------------------------
if [ ! -d "$HOME/storage" ] && command -v termux-setup-storage >/dev/null 2>&1; then
    say "depolama izni isteniyor (telefonda onayla)"
    termux-setup-storage >/dev/null 2>&1 || say "UYARI: depolama izni verilmedi (sonra tekrar denenebilir)"
fi

# ------------------------------------------------------------------------------------------
# 5) Buraya kendi ek kurulum adımlarını yaz
# ------------------------------------------------------------------------------------------
# Örnekler:
#   pkg install -y nodejs-lts          # Node.js
#   pip install requests               # Python paketi
#   pkg install -y x11-repo termux-x11-nightly xfce4   # X11 masaüstü
#   proot-distro install debian        # konteyner

say "birinci aşama tamam"
exit 0
