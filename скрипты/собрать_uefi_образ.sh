#!/usr/bin/env bash
# ДубинаОС — сборка UEFI-загрузочного образа диска.
# Лицензия: GPL-3.0-or-later
#
# Что делает:
#   1. Собирает ядро (release) → ELF.
#   2. Собирает UEFI-загрузчик → BOOTX64.efi.
#   3. Создаёт FAT32-образ (ESP) с /EFI/BOOT/BOOTX64.EFI и /yadro.elf.
#   4. Оборачивает его в GPT-разметку → диск, с которого UEFI грузится.
#
# Зависимости: nasm (для legacy-пути не нужен здесь), dosfstools (mkfs.vfat),
#              mtools (mcopy), parted или sgdisk, и наша Rust-сборка.
#
# Результат: сборка/dubina_uefi.img — raw GPT-диск.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PROFILE="${1:-release}"
BUILD_DIR="$ROOT/сборка"
mkdir -p "$BUILD_DIR"

ESP_IMG="$BUILD_DIR/esp.img"
DISK_IMG="$BUILD_DIR/dubina_uefi.img"

# 1. Сборка ядра. Тот же ELF, что использует Legacy-путь, поэтому держим
# в target/x86_64-unknown-none/release.
KERNEL_TARGET="x86_64-unknown-none"
KERNEL_ELF="$ROOT/target/$KERNEL_TARGET/$PROFILE/yadro"
echo "[uefi] cargo build ядра ($PROFILE)..."
CARGO_KERNEL=(--target "$KERNEL_TARGET" -p dubina-kernel)
if [[ "$PROFILE" == "release" ]]; then
    CARGO_KERNEL+=(--release)
fi
cargo build "${CARGO_KERNEL[@]}"

# 2. Сборка UEFI-загрузчика.
LOADER_TARGET="x86_64-unknown-uefi"
LOADER_EFI="$ROOT/target/$LOADER_TARGET/$PROFILE/BOOTX64.efi"
echo "[uefi] cargo build загрузчика ($PROFILE)..."
CARGO_LOADER=(--target "$LOADER_TARGET" -p dubina-bootloader-uefi)
if [[ "$PROFILE" == "release" ]]; then
    CARGO_LOADER+=(--release)
fi
cargo build "${CARGO_LOADER[@]}"

if [[ ! -f "$KERNEL_ELF" ]]; then
    echo "ОШИБКА: ядро не собралось: $KERNEL_ELF" >&2
    exit 1
fi
if [[ ! -f "$LOADER_EFI" ]]; then
    echo "ОШИБКА: UEFI-загрузчик не собрался: $LOADER_EFI" >&2
    exit 1
fi

# 3. Собираем FAT32-образ ESP. UEFI требует именно FAT (FAT12/16/32).
ESP_SIZE_MB=64
echo "[uefi] создание FAT32-ESP ($ESP_SIZE_MB МиБ)..."
truncate -s "${ESP_SIZE_MB}M" "$ESP_IMG"
mkfs.vfat -F 32 -n "DUBINAOS" "$ESP_IMG" >/dev/null

mmd -i "$ESP_IMG" ::/EFI ::/EFI/BOOT
mcopy -i "$ESP_IMG" "$LOADER_EFI" ::/EFI/BOOT/BOOTX64.EFI
mcopy -i "$ESP_IMG" "$KERNEL_ELF" ::/yadro.elf

# 4. GPT-обёртка. Используем sgdisk, если он есть, иначе parted.
DISK_SIZE_MB=$(( ESP_SIZE_MB + 4 ))   # ESP + 1 МиБ под заголовки GPT
echo "[uefi] создание GPT-диска ($DISK_SIZE_MB МиБ)..."
truncate -s "${DISK_SIZE_MB}M" "$DISK_IMG"

# Запишем ESP, начиная с 1 МиБ (стандартное выравнивание GPT).
ESP_START_MB=1
dd if="$ESP_IMG" of="$DISK_IMG" bs=1M seek="$ESP_START_MB" conv=notrunc status=none

if command -v sgdisk >/dev/null 2>&1; then
    sgdisk --zap-all "$DISK_IMG" >/dev/null
    sgdisk \
        --new=1:$(( ESP_START_MB * 2048 )):$(( (ESP_START_MB + ESP_SIZE_MB) * 2048 - 1 )) \
        --typecode=1:EF00 \
        --change-name=1:"DUBINAOS_ESP" \
        "$DISK_IMG" >/dev/null
elif command -v parted >/dev/null 2>&1; then
    parted -s "$DISK_IMG" mklabel gpt
    parted -s "$DISK_IMG" mkpart ESP fat32 ${ESP_START_MB}MiB $(( ESP_START_MB + ESP_SIZE_MB ))MiB
    parted -s "$DISK_IMG" set 1 esp on
else
    echo "ОШИБКА: нужен sgdisk или parted, чтобы создать GPT-разметку." >&2
    exit 1
fi

echo "Готово:"
echo "  $LOADER_EFI"
echo "  $KERNEL_ELF"
echo "  $ESP_IMG"
echo "  $DISK_IMG ($(stat -c%s "$DISK_IMG") байт)"
