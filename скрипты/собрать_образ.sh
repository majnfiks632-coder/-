#!/usr/bin/env bash
# ДубинаОС — сборка собственного загрузочного образа диска.
# Лицензия: GPL-3.0-or-later
#
# Образ имеет такую структуру (в секторах по 512 байт):
#
#   LBA 0          : MBR (mbr.bin), ровно 512 байт.
#   LBA 1..63      : Stage2 (stage2.bin), до 31.5 КиБ.
#   LBA 64..       : Ядро (yadro.bin), плоский raw-бинарник, объект ELF
#                    предварительно конвертируется через objcopy.
#
# Stage2 жёстко знает, что ядро начинается с LBA 64 — это значение задаётся
# в stage2.s через макрос KERNEL_START_LBA.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PROFILE="${1:-debug}"
BUILD_DIR="$ROOT/сборка"
BOOT_DIR="$ROOT/загрузчик/x86_legacy"

mkdir -p "$BUILD_DIR"

# 1. Сборка ядра нужного профиля.
CARGO_FLAGS=(--target x86_64-unknown-none -p dubina-kernel)
KERNEL_ELF="target/x86_64-unknown-none/$PROFILE/yadro"
if [[ "$PROFILE" == "release" ]]; then
    CARGO_FLAGS+=(--release)
fi
echo "[собрать_образ] cargo build ($PROFILE)..."
cargo build "${CARGO_FLAGS[@]}"

# 2. Извлечение raw-бинарника из ELF.
echo "[собрать_образ] objcopy ELF -> raw"
objcopy -O binary "$KERNEL_ELF" "$BUILD_DIR/yadro.bin"

# 3. Сборка MBR.
echo "[собрать_образ] nasm mbr.s"
nasm -f bin "$BOOT_DIR/mbr.s" -o "$BUILD_DIR/mbr.bin"

# 4. Сборка Stage2. Заранее знаем размер ядра в секторах,
#    передаём в NASM через -D, чтобы Stage2 читал ровно столько,
#    сколько нужно.
size_kernel=$(stat -c%s "$BUILD_DIR/yadro.bin")
sectors_kernel=$(( (size_kernel + 511) / 512 ))
# Округляем до кратности 8 секторов с запасом.
sectors_to_read=$(( ((sectors_kernel + 31) / 32) * 32 ))

echo "[собрать_образ] nasm stage2.s (KERNEL_SECTORS=$sectors_to_read)"
nasm -f bin "$BOOT_DIR/stage2.s" -DKERNEL_SECTORS="$sectors_to_read" -o "$BUILD_DIR/stage2.bin"

# Проверки размеров
size_mbr=$(stat -c%s "$BUILD_DIR/mbr.bin")
size_stage2=$(stat -c%s "$BUILD_DIR/stage2.bin")

if [[ "$size_mbr" -ne 512 ]]; then
    echo "ОШИБКА: MBR должен быть ровно 512 байт, а получился $size_mbr" >&2
    exit 1
fi

# Stage2 максимум 31.5 КиБ (63 сектора): между MBR и ядром по LBA 64.
max_stage2=$(( 63 * 512 ))
if [[ "$size_stage2" -gt "$max_stage2" ]]; then
    echo "ОШИБКА: Stage2 ($size_stage2 байт) больше чем $max_stage2" >&2
    exit 1
fi

# Размер ядра не должен превышать 512 КиБ (1024 сектора): Stage2 читает
# его в нижнюю память (под 1 МиБ) сегментно через INT 13h, а сегментный
# регистр 16-бит и быстро упирается в потолок.
if [[ "$sectors_to_read" -gt 1024 ]]; then
    echo "ОШИБКА: ядро занимает $sectors_to_read секторов," >&2
    echo "        Stage2 поддерживает максимум 1024 (512 КиБ)." >&2
    exit 1
fi

# 5. Сборка единого образа диска: MBR + Stage2 + padding до LBA 64 + ядро.
IMG="$BUILD_DIR/dubina.img"
echo "[собрать_образ] склейка $IMG"
{
    cat "$BUILD_DIR/mbr.bin"
    cat "$BUILD_DIR/stage2.bin"
    dd if=/dev/zero bs=1 count=$(( 64 * 512 - 512 - size_stage2 )) status=none
    cat "$BUILD_DIR/yadro.bin"
} > "$IMG"

# Дополнительно дополняем образ до круглого размера, чтобы был кратен МиБ.
size_img=$(stat -c%s "$IMG")
aligned=$(( ((size_img + 1024 * 1024 - 1) / (1024 * 1024)) * 1024 * 1024 ))
if [[ "$size_img" -lt "$aligned" ]]; then
    dd if=/dev/zero bs=1 count=$((aligned - size_img)) >> "$IMG" status=none
fi

echo "Готово: $IMG ($(stat -c%s "$IMG") байт)"
echo "  MBR:    $size_mbr байт"
echo "  Stage2: $size_stage2 байт"
echo "  Ядро:   $size_kernel байт ($sectors_kernel секторов)"
