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

# 3. Собираем FAT-образ ESP. UEFI требует FAT (FAT12/16/32). FAT32 имеет
# минимум ~64 МиБ, поэтому из-за пары сотен КБ полезных данных вся
# флешка раздувается до 64 МиБ. Чтобы образ был маленьким, считаем
# реальный размер содержимого и ставим FAT16/FAT12 — у них минимум
# в десятки раз меньше. Можно переопределить через ESP_SIZE_MB=… (env).
LOADER_BYTES=$(stat -c%s "$LOADER_EFI")
KERNEL_BYTES=$(stat -c%s "$KERNEL_ELF")
PAYLOAD_BYTES=$(( LOADER_BYTES + KERNEL_BYTES ))
# +512 КиБ под FAT-таблицы и каталоги, +20% запас, округление вверх до МиБ.
ESP_AUTO_MB=$(( (PAYLOAD_BYTES + 524288) * 12 / 10 / 1048576 + 1 ))
# FAT16 минимум — ~2 МиБ при кластере 512 Б (4085 кластеров минимум).
if [[ "$ESP_AUTO_MB" -lt 4 ]]; then
    ESP_AUTO_MB=4
fi
ESP_SIZE_MB="${ESP_SIZE_MB:-$ESP_AUTO_MB}"
echo "[uefi] создание FAT-ESP ($ESP_SIZE_MB МиБ; payload $PAYLOAD_BYTES Б)..."
truncate -s "${ESP_SIZE_MB}M" "$ESP_IMG"
# -F 16: FAT16, минимум ~2 МиБ при -s 1 (один сектор на кластер).
# UEFI-фирмварь обязана уметь читать FAT12/16/32 — это часть UEFI Spec.
mkfs.vfat -F 16 -s 1 -n "DUBINAOS" "$ESP_IMG" >/dev/null

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
