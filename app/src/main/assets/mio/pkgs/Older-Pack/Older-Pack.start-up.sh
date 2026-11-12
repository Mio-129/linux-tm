#!/data/data/com.termux/files/usr/bin/sh
#
# Older-Pack — başlangıç betiği (.start-up.sh)
#
# Bu betik:
#   * paket kurulduktan sonra Termux her açıldığında,
#   * paket her güncellendiğinde (VERSION değiştiğinde),
#   * "Paketler > Older-Pack > Başlangıç" ile elle çalıştırıldığında
# çalışır. Uygulama açılışını yavaşlatmamak için arka planda, `sh` ile çalıştırılır.
#
# Buraya "her açılışta hazır olması gereken" servisler ve ayarlar gelir.
# Uzun işleri (büyük kurulum gibi) buraya KOYMA, onlar .a.sh içindir.

set -u

AD="Older-Pack.start-up"
say() { printf '[%s] %s\n' "$AD" "$*"; }

if [ -z "${PREFIX:-}" ]; then
    PREFIX=/data/data/com.termux/files/usr
fi

say "çalıştı ($(date 2>/dev/null))"

# ------------------------------------------------------------------------------------------
# 1) Yollar hazır mı?
# ------------------------------------------------------------------------------------------
case ":$PATH:" in
    *":$HOME/.local/bin:"*) ;;
    *) export PATH="$HOME/.local/bin:$PATH" ;;
esac

# ------------------------------------------------------------------------------------------
# 2) Servisleri başlat (çalışmıyorsa)
# ------------------------------------------------------------------------------------------
# SSH sunucusu kuruluysa ve çalışmıyorsa başlat
if [ -x "$PREFIX/bin/sshd" ]; then
    if ! pgrep -f "sshd" >/dev/null 2>&1; then
        sshd 2>/dev/null && say "sshd başlatıldı" || say "UYARI: sshd başlatılamadı"
    fi
fi

# crond (zamanlanmış görevler) kuruluysa
if [ -x "$PREFIX/bin/crond" ]; then
    if ! pgrep -f "crond" >/dev/null 2>&1; then
        crond 2>/dev/null && say "crond başlatıldı"
    fi
fi
# NOT: termux-x11 gibi uygulamaları burada başlatmak istersen şunu kullan:
#   pgrep -f "termux-x11" >/dev/null || (termux-x11 :0 >/dev/null 2>&1 &)

# ------------------------------------------------------------------------------------------
# 3) Temizlik: eski geçici dosyalar
# ------------------------------------------------------------------------------------------
if [ -d "$PREFIX/tmp" ]; then
    find "$PREFIX/tmp" -maxdepth 1 -type f -mtime +3 -name 'older-*' -delete 2>/dev/null
fi

# ------------------------------------------------------------------------------------------
# 4) Kendi ek başlangıç işlerini buraya yaz
# ------------------------------------------------------------------------------------------
# Örnekler:
#   pgrep -f "syncthing" >/dev/null || (syncthing serve --no-browser >/dev/null 2>&1 &)
#   termux-toast "Older-Pack hazır"

say "tamam"
exit 0
