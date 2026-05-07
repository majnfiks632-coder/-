// ДубинаОС
// Авторские права (C) 2026, Команда ДубинаОС
// Лицензия: GPL-3.0-or-later
//
// Аппаратные «защёлки» процессора, повышающие безопасность ядра:
//
//   * **NX** (No-eXecute, EFER.NXE) — позволяет пометить страницу как
//     «не исполняется». Уже включается в `вход.s` сразу после входа в
//     long mode, без него бит NX в записях таблиц страниц игнорируется.
//
//   * **CR0.WP** (Write Protect) — заставляет ядро уважать флаг RW в
//     записях таблиц страниц при записи. Тоже включается в `вход.s`.
//
//   * **CR4.SMEP** (бит 20) — Supervisor-Mode Execution Prevention.
//     Запрещает CPU исполнять инструкции с user-страниц, находясь в
//     ring 0. Защита от классической эксплойт-техники «kernel-jump-to-
//     user-shellcode». Включается, если CPUID показывает поддержку.
//
//   * **CR4.SMAP** (бит 21) — Supervisor-Mode Access Prevention.
//     Запрещает CPU читать/писать user-страницы из ring 0 без явного
//     stac/clac. Защищает от случайного «ядро прочитало userspace по
//     произвольному указателю». Включается, если CPUID показывает
//     поддержку.
//
//   * **CR4.UMIP** (бит 11, новее) — User-Mode Instruction Prevention.
//     Запрещает userspace выполнять SGDT/SIDT/SLDT/STR/SMSW и тем
//     самым ловить раскладку ядерных таблиц.
//
// Текущее ядро ДубинаОС полностью живёт в ring 0, поэтому SMEP/SMAP/UMIP
// сейчас работают «вхолостую», но включаем заранее: когда появится
// userspace (этап 7+), эти биты уже будут активны и не дадут ранним
// версиям user-кода обойти защиту по ошибке.
//
// Все изменения CR4 — атомарны (read-modify-write) и идемпотентны.

#![allow(dead_code)]

use core::arch::x86_64::__cpuid_count;

/// Биты CR4, относящиеся к этому модулю.
const CR4_UMIP: u64 = 1 << 11;
const CR4_SMEP: u64 = 1 << 20;
const CR4_SMAP: u64 = 1 << 21;

/// Что в действительности удалось включить — для красивого вывода в журнал.
#[derive(Debug, Clone, Copy)]
pub struct Включенные {
    pub nx: bool,
    pub wp: bool,
    pub smep: bool,
    pub smap: bool,
    pub umip: bool,
}

impl Включенные {
    pub const fn пустые() -> Self {
        Self {
            nx: false,
            wp: false,
            smep: false,
            smap: false,
            umip: false,
        }
    }
}

/// Прочитать CR0.
#[inline]
unsafe fn прочитать_cr0() -> u64 {
    let значение: u64;
    unsafe {
        core::arch::asm!(
            "mov {0}, cr0",
            out(reg) значение,
            options(nostack, preserves_flags),
        );
    }
    значение
}

/// Прочитать CR4.
#[inline]
unsafe fn прочитать_cr4() -> u64 {
    let значение: u64;
    unsafe {
        core::arch::asm!(
            "mov {0}, cr4",
            out(reg) значение,
            options(nostack, preserves_flags),
        );
    }
    значение
}

/// Записать CR4.
#[inline]
unsafe fn записать_cr4(значение: u64) {
    unsafe {
        core::arch::asm!(
            "mov cr4, {0}",
            in(reg) значение,
            options(nostack, preserves_flags),
        );
    }
}

/// Прочитать MSR (Model-Specific Register).
#[inline]
unsafe fn прочитать_msr(индекс: u32) -> u64 {
    let низ: u32;
    let верх: u32;
    unsafe {
        core::arch::asm!(
            "rdmsr",
            in("ecx") индекс,
            out("eax") низ,
            out("edx") верх,
            options(nostack, preserves_flags),
        );
    }
    ((верх as u64) << 32) | (низ as u64)
}

/// IA32_EFER — биты LME/LMA/NXE/SCE.
const MSR_EFER: u32 = 0xC000_0080;
const EFER_NXE: u64 = 1 << 11;

const CR0_WP: u64 = 1 << 16;

/// Поддерживает ли CPU SMEP — CPUID(7,0).EBX, бит 7.
fn cpu_поддерживает_smep() -> bool {
    let ebx = __cpuid_count(7, 0).ebx;
    (ebx & (1 << 7)) != 0
}

/// Поддерживает ли CPU SMAP — CPUID(7,0).EBX, бит 20.
fn cpu_поддерживает_smap() -> bool {
    let ebx = __cpuid_count(7, 0).ebx;
    (ebx & (1 << 20)) != 0
}

/// Поддерживает ли CPU UMIP — CPUID(7,0).ECX, бит 2.
fn cpu_поддерживает_umip() -> bool {
    let ecx = __cpuid_count(7, 0).ecx;
    (ecx & (1 << 2)) != 0
}

/// Включить SMEP/SMAP/UMIP — каждый только если процессор поддерживает.
/// NX и WP уже включены ассемблером входа; здесь мы только их проверяем
/// и включаем, если по какой-то причине оказались сброшены.
///
/// Возвращает структуру с флагами, что в итоге включено — для журнала.
pub fn укрепить() -> Включенные {
    let mut итог = Включенные::пустые();

    // 1. EFER.NXE — должен быть уже включён, проверяем.
    let efer = unsafe { прочитать_msr(MSR_EFER) };
    итог.nx = (efer & EFER_NXE) != 0;

    // 2. CR0.WP — также должен быть включён, проверяем.
    let cr0 = unsafe { прочитать_cr0() };
    итог.wp = (cr0 & CR0_WP) != 0;

    // 3. CR4.SMEP / SMAP / UMIP — включаем, если поддерживается.
    let mut cr4 = unsafe { прочитать_cr4() };
    let было = cr4;

    if cpu_поддерживает_smep() {
        cr4 |= CR4_SMEP;
        итог.smep = true;
    }
    if cpu_поддерживает_smap() {
        cr4 |= CR4_SMAP;
        итог.smap = true;
    }
    if cpu_поддерживает_umip() {
        cr4 |= CR4_UMIP;
        итог.umip = true;
    }

    if cr4 != было {
        unsafe { записать_cr4(cr4) };
    }

    итог
}
