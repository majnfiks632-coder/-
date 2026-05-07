#!/usr/bin/env bash
# ДубинаОС
# Лицензия: GPL-3.0-or-later
#
# Запуск ДубинаОС в QEMU x86_64 на собственном загрузчике (без GRUB).
#
# По умолчанию используется raw-образ диска `сборка/dubina.img`, в котором
# в первых 512 байтах лежит наш MBR, в следующих 31.5 КиБ — Stage2,
# а ядро — начиная с LBA 64. Образ собирается скриптом `собрать_образ.sh`.
#
# Использование:
#   скрипты/запустить_qemu.sh                # интерактивно (Ctrl-A X для выхода)
#   скрипты/запустить_qemu.sh --gui          # с графической консолью VGA
#   скрипты/запустить_qemu.sh --test         # авто-завершение через 15 сек (CI)
#   скрипты/запустить_qemu.sh --grub         # альтернативный путь: ISO с GRUB
#   скрипты/запустить_qemu.sh --debug        # с QEMU-монитором, для отладки

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

USE_GRUB=0
MODE="interactive"

for arg in "$@"; do
    case "$arg" in
        --grub)  USE_GRUB=1 ;;
        --gui)   MODE="gui" ;;
        --test)  MODE="test" ;;
        --debug) MODE="debug" ;;
        *)       echo "Неизвестный аргумент: $arg" >&2; exit 2 ;;
    esac
done

QEMU_OPTS=(-m 256M -no-reboot -no-shutdown -d guest_errors -smp 1)

if [[ "$USE_GRUB" -eq 1 ]]; then
    ISO="сборка/dubina.iso"
    if [[ ! -f "$ISO" ]]; then
        bash скрипты/собрать_iso.sh
    fi
    QEMU_OPTS+=(-cdrom "$ISO" -boot d)
else
    IMG="сборка/dubina.img"
    if [[ ! -f "$IMG" ]]; then
        bash скрипты/собрать_образ.sh
    fi
    QEMU_OPTS+=(-drive "format=raw,file=$IMG" -boot c)
fi

case "$MODE" in
    gui)
        QEMU_OPTS+=(-serial stdio)
        ;;
    test)
        QEMU_OPTS+=(-nographic -serial mon:stdio)
        echo "Запуск в тестовом режиме (15 сек таймаут)..."
        RESULT=0
        timeout 15 qemu-system-x86_64 "${QEMU_OPTS[@]}" || RESULT=$?
        if [[ "$RESULT" -eq 124 ]]; then
            echo "Таймаут — QEMU остановлена принудительно (норма для теста загрузки)."
        fi
        exit 0
        ;;
    debug)
        QEMU_OPTS+=(-nographic -serial mon:stdio -s -S)
        echo "Отладочный запуск: QEMU остановлена на старте, подключайся через"
        echo "  gdb -ex 'target remote :1234'"
        ;;
    interactive)
        QEMU_OPTS+=(-nographic -serial mon:stdio)
        ;;
esac

exec qemu-system-x86_64 "${QEMU_OPTS[@]}"
