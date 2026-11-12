#!/data/data/com.termux/files/usr/bin/sh
#
# Older-Tool — ikinci aşama betiği (.b.sh)
#
# Bu betik YALNIZCA Older-Pack.a.sh başarıyla (çıkış kodu 0) bittiyse çalışır.
# Yani "paket kuruldu, şimdi kurulum sonrası ayarları yap" adımıdır:
#   * kabuk ve git takma adları,
#   * ortam değişkenleri (~/.bashrc, ~/.zshrc varsa),
#   * kendi araçların (~/.local/bin),
#   * yapılandırma dosyaları.
#
# Yine tekrar çalıştırılabilir olmalı: aynı satırı iki kez eklememeli.

set -u

AD="Older-Tool"
say() { printf '[%s] %s\n' "$AD" "$*"; }

if [ -z "${PREFIX:-}" ]; then
    PREFIX=/data/data/com.termux/files/usr
fi

say "ikinci aşama başladı ($(date 2>/dev/null))"

# ------------------------------------------------------------------------------------------
# 1) Araç gerçekten kurulu mu? (Older-Pack'in işini yarım bırakmadığını doğrula)
# ------------------------------------------------------------------------------------------
if ! command -v git >/dev/null 2>&1; then
    say "HATA: git bulunamadı, Older-Pack birinci aşaması tamamlanmamış görünüyor."
    exit 1
fi
say "git: $(git --version 2>/dev/null)"

# ------------------------------------------------------------------------------------------
# 2) Satırı dosyaya bir kez ekle (idempotent)
# ------------------------------------------------------------------------------------------
append_once() {
    dosya="$1"
    satir="$2"
    [ -f "$dosya" ] || : > "$dosya"
    if ! grep -Fqx "$satir" "$dosya" 2>/dev/null; then
        printf '%s\n' "$satir" >> "$dosya"
        say "eklendi: $dosya -> $satir"
    fi
}

# ------------------------------------------------------------------------------------------
# 3) Ortam değişkenleri ve takma adlar
# ------------------------------------------------------------------------------------------
for rc in "$HOME/.bashrc" "$HOME/.zshrc"; do
    [ -f "$rc" ] || continue
    append_once "$rc" 'export PATH="$HOME/.local/bin:$PATH"'
    append_once "$rc" 'export LANG=en_US.UTF-8'
    append_once "$rc" 'alias ll="ls -alF"'
    append_once "$rc" 'alias gs="git status"'
done

# Profilcu bir kabuk yoksa .profile oluştur
if [ ! -f "$HOME/.bashrc" ] && [ ! -f "$HOME/.zshrc" ] && [ ! -f "$HOME/.profile" ]; then
    append_once "$HOME/.profile" 'export PATH="$HOME/.local/bin:$PATH"'
fi

# ------------------------------------------------------------------------------------------
# 4) git varsayılanları
# ------------------------------------------------------------------------------------------
git config --global init.defaultBranch main >/dev/null 2>&1 && say "git: varsayılan dal main"
git config --global pull.rebase false >/dev/null 2>&1 && say "git: pull.rebase false"
git config --global core.pager less >/dev/null 2>&1

# ------------------------------------------------------------------------------------------
# 5) Kendi komutların (~/.local/bin) — örnek bir yardımcı komut
# ------------------------------------------------------------------------------------------
mkdir -p "$HOME/.local/bin" || {
    say "HATA: ~/.local/bin oluşturulamadı."
    exit 1
}

YARDIMCI="$HOME/.local/bin/mio-bilgi"
if [ ! -x "$YARDIMCI" ]; then
    cat > "$YARDIMCI" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
# Older-Tool tarafından oluşturuldu: kısa sistem bilgisi
echo "Termux : ${PREFIX:-?}"
echo "Ev     : $HOME"
echo "Tarih  : $(date 2>/dev/null)"
echo "Depo   : $(df -h "$HOME" 2>/dev/null | tail -n 1)"
EOF
    chmod +x "$YARDIMCI"
    say "yardımcı komut oluşturuldu: ~/.local/bin/mio-bilgi"
fi

# ------------------------------------------------------------------------------------------
# 6) Kendi ek adımlarını buraya yaz
# ------------------------------------------------------------------------------------------
# Örnekler:
#   pkg install -y openssh && sshd
#   echo 'export EDITOR=nano' >> ~/.bashrc
#   termux-setup-storage

say "ikinci aşama tamam"
exit 0
