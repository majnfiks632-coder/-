# ДубинаОС — главный Makefile
# Лицензия: GPL-3.0-or-later
#
# Цели:
#   make                  — сборка ядра x86_64 (debug)
#   make release          — сборка ядра x86_64 (release)
#   make aarch64          — сборка ядра aarch64
#   make образ            — собрать raw-образ диска (свой MBR + Stage2 + ядро)
#   make iso              — альтернатива: ISO через GRUB Multiboot2
#   make запуск           — запустить собственный образ в QEMU x86_64
#   make запуск-grub      — запустить GRUB ISO в QEMU (тест совместимости)
#   make запуск-aarch64   — запуск ядра aarch64 в QEMU virt
#   make тесты            — все юнит-тесты компилятора и рантайма
#   make проверка         — cargo check для всех целей
#   make чисто            — удалить артефакты сборки
#   make помощь           — показать это меню

.PHONY: all release aarch64 образ iso запуск запуск-grub запуск-aarch64 тесты проверка чисто помощь

all: ядро

ядро:
	cargo build --target x86_64-unknown-none -p dubina-kernel

release:
	cargo build --target x86_64-unknown-none -p dubina-kernel --release

aarch64:
	cargo build --target aarch64-unknown-none -p dubina-kernel

образ:
	bash скрипты/собрать_образ.sh release

iso:
	bash скрипты/собрать_iso.sh

запуск: образ
	bash скрипты/запустить_qemu.sh --test

запуск-grub: iso
	bash скрипты/запустить_qemu.sh --grub --test

запуск-aarch64: aarch64
	qemu-system-aarch64 -machine virt -cpu cortex-a72 -m 256M -display none \
		-kernel target/aarch64-unknown-none/debug/yadro -serial mon:stdio \
		-no-reboot

тесты:
	cargo test -p dubina-compiler
	cargo test -p dubina-runtime

проверка:
	cargo check --target x86_64-unknown-none -p dubina-kernel
	cargo check --target aarch64-unknown-none -p dubina-kernel
	cargo check -p dubina-compiler
	cargo check -p dubina-runtime

чисто:
	cargo clean
	rm -rf сборка

помощь:
	@grep -E '^# ' Makefile | head -20
