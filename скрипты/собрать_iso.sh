#!/usr/bin/env bash
# ДубинаОС
# Авторские права (C) 2026, Команда ДубинаОС
# Лицензия: GPL-3.0-or-later
#
# Скрипт сборки загрузочного ISO-образа ДубинаОС для x86_64.
#
# Использование:
#   скрипты/собрать_iso.sh [release|debug]
#
# Создаёт сборка/dubina.iso, который можно запустить:
#   qemu-system-x86_64 -cdrom сборка/dubina.iso -nographic -serial mon:stdio
#   или загрузить с реальной флешки.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PROFILE="${1:-debug}"
TARGET="x86_64-unknown-none"

if [ "$PROFILE" = "release" ]; then
    cargo build --target "$TARGET" -p dubina-kernel --release
    KERNEL="target/$TARGET/release/yadro"
else
    cargo build --target "$TARGET" -p dubina-kernel
    KERNEL="target/$TARGET/debug/yadro"
fi

if [ ! -f "$KERNEL" ]; then
    echo "Ошибка: ядро не собралось ($KERNEL)" >&2
    exit 1
fi

ISODIR="сборка/iso"
mkdir -p "$ISODIR/boot/grub"
cp "$KERNEL" "$ISODIR/boot/yadro"

cat > "$ISODIR/boot/grub/grub.cfg" <<EOF
set timeout=0
set default=0

menuentry "ДубинаОС" {
    multiboot2 /boot/yadro
    boot
}
EOF

ISO="сборка/dubina.iso"
echo "Создаю ISO: $ISO"
grub-mkrescue -o "$ISO" "$ISODIR" 2>&1 | grep -v -E '(xorriso|libisoburn|libisofs|libburn|^\s*$)' || true

echo "Готово: $ISO"
ls -la "$ISO"
