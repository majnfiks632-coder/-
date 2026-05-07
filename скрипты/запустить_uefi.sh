#!/usr/bin/env bash
# ДубинаОС
# Лицензия: GPL-3.0-or-later
#
# Запуск ДубинаОС в QEMU x86_64 с UEFI-прошивкой OVMF.
#
# Перед первым запуском нужен пакет `ovmf` (Debian/Ubuntu) или `edk2-ovmf`
# (Fedora/Arch). Скрипт сам ищет OVMF_CODE.fd / OVMF_VARS.fd по типичным путям.
#
# Использование:
#   скрипты/запустить_uefi.sh                # интерактивно (Ctrl-A X выход)
#   скрипты/запустить_uefi.sh --gui          # с графической консолью
#   скрипты/запустить_uefi.sh --test         # авто-завершение через 20 сек

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

MODE="interactive"
for arg in "$@"; do
    case "$arg" in
        --gui)   MODE="gui" ;;
        --test)  MODE="test" ;;
        *)       echo "Неизвестный аргумент: $arg" >&2; exit 2 ;;
    esac
done

DISK="сборка/dubina_uefi.img"
if [[ ! -f "$DISK" ]]; then
    bash скрипты/собрать_uefi_образ.sh
fi

# Поиск OVMF.
OVMF_CANDIDATES=(
    /usr/share/OVMF/OVMF_CODE_4M.fd
    /usr/share/OVMF/OVMF_CODE.fd
    /usr/share/edk2/x64/OVMF_CODE.fd
    /usr/share/edk2/ovmf/OVMF_CODE.fd
    /usr/share/qemu/OVMF.fd
)
OVMF_CODE=""
for c in "${OVMF_CANDIDATES[@]}"; do
    if [[ -f "$c" ]]; then OVMF_CODE="$c"; break; fi
done
if [[ -z "$OVMF_CODE" ]]; then
    echo "ОШИБКА: не найден OVMF_CODE.fd. Установи пакет ovmf:" >&2
    echo "  Debian/Ubuntu: sudo apt install ovmf" >&2
    echo "  Fedora:        sudo dnf install edk2-ovmf" >&2
    echo "  Arch:          sudo pacman -S edk2-ovmf" >&2
    exit 3
fi

VARS_CANDIDATES=(
    /usr/share/OVMF/OVMF_VARS_4M.fd
    /usr/share/OVMF/OVMF_VARS.fd
    /usr/share/edk2/x64/OVMF_VARS.fd
    /usr/share/edk2/ovmf/OVMF_VARS.fd
)
OVMF_VARS_SRC=""
for c in "${VARS_CANDIDATES[@]}"; do
    if [[ -f "$c" ]]; then OVMF_VARS_SRC="$c"; break; fi
done

# Делаем локальную копию VARS (UEFI пишет в неё переменные).
mkdir -p сборка
LOCAL_VARS="сборка/OVMF_VARS.fd"
if [[ ! -f "$LOCAL_VARS" && -n "$OVMF_VARS_SRC" ]]; then
    cp "$OVMF_VARS_SRC" "$LOCAL_VARS"
fi

QEMU_OPTS=(
    -machine q35
    -cpu qemu64
    -m 256M
    -no-reboot
    -no-shutdown
    -d guest_errors
    -smp 1
    -drive "if=pflash,format=raw,readonly=on,file=$OVMF_CODE"
)
if [[ -f "$LOCAL_VARS" ]]; then
    QEMU_OPTS+=(-drive "if=pflash,format=raw,file=$LOCAL_VARS")
fi
QEMU_OPTS+=(-drive "format=raw,file=$DISK,if=ide")

case "$MODE" in
    gui)
        QEMU_OPTS+=(-serial stdio)
        ;;
    test)
        QEMU_OPTS+=(-nographic -serial mon:stdio)
        echo "Запуск в тестовом режиме (20 сек таймаут)..."
        RESULT=0
        timeout 20 qemu-system-x86_64 "${QEMU_OPTS[@]}" || RESULT=$?
        if [[ "$RESULT" -eq 124 ]]; then
            echo "Таймаут — QEMU остановлена принудительно (норма для теста загрузки)."
        fi
        exit 0
        ;;
    interactive)
        QEMU_OPTS+=(-nographic -serial mon:stdio)
        ;;
esac

exec qemu-system-x86_64 "${QEMU_OPTS[@]}"
