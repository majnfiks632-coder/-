// ДубинаОС
// Авторские права (C) 2026, Команда ДубинаОС
// Лицензия: GPL-3.0-or-later
//
// Local APIC — Advanced Programmable Interrupt Controller (на ядре CPU).
//
// LAPIC отвечает за:
//   * приём прерываний от IO APIC и других CPU (IPI),
//   * локальный таймер (нам пока не нужен — мы оставляем PIT),
//   * EOI (End-Of-Interrupt) для всех прерываний в режиме APIC.
//
// Адрес LAPIC берётся из ACPI MADT или из MSR IA32_APIC_BASE (0x1B).
// На большинстве x86_64 машин это 0xFEE00000.
//
// Все идентификаторы и сообщения — на русском.

#![allow(dead_code)]

use core::arch::asm;
use core::sync::atomic::{AtomicBool, AtomicU64, Ordering};

// Регистры LAPIC (смещения от базы).
pub const РЕГ_ID: u32 = 0x020;
pub const РЕГ_ВЕРСИЯ: u32 = 0x030;
pub const РЕГ_TPR: u32 = 0x080;       // Task Priority Register
pub const РЕГ_EOI: u32 = 0x0B0;
pub const РЕГ_LDR: u32 = 0x0D0;       // Logical Destination
pub const РЕГ_DFR: u32 = 0x0E0;       // Destination Format
pub const РЕГ_SVR: u32 = 0x0F0;       // Spurious Vector Register
pub const РЕГ_ESR: u32 = 0x280;       // Error Status
pub const РЕГ_ICR_LO: u32 = 0x300;
pub const РЕГ_ICR_HI: u32 = 0x310;
pub const РЕГ_LVT_TIMER: u32 = 0x320;
pub const РЕГ_LVT_LINT0: u32 = 0x350;
pub const РЕГ_LVT_LINT1: u32 = 0x360;
pub const РЕГ_LVT_ERROR: u32 = 0x370;
pub const РЕГ_TIMER_INIT: u32 = 0x380;
pub const РЕГ_TIMER_CUR: u32 = 0x390;
pub const РЕГ_TIMER_DIV: u32 = 0x3E0;

const SVR_БИТ_РАЗРЕШЕНИЯ: u32 = 1 << 8;

/// MSR IA32_APIC_BASE.
const MSR_APIC_BASE: u32 = 0x1B;
const APIC_BASE_BIT_GLOBAL_ENABLE: u64 = 1 << 11;
const APIC_BASE_BIT_BSP: u64 = 1 << 8;

static БАЗА: AtomicU64 = AtomicU64::new(0);
static ИНИЦИАЛИЗИРОВАН: AtomicBool = AtomicBool::new(false);

/// Прочитать MSR.
unsafe fn rdmsr(индекс: u32) -> u64 {
    let lo: u32;
    let hi: u32;
    unsafe {
        asm!(
            "rdmsr",
            in("ecx") индекс,
            out("eax") lo,
            out("edx") hi,
            options(nomem, nostack, preserves_flags),
        );
    }
    ((hi as u64) << 32) | (lo as u64)
}

/// Записать MSR.
unsafe fn wrmsr(индекс: u32, значение: u64) {
    let lo = значение as u32;
    let hi = (значение >> 32) as u32;
    unsafe {
        asm!(
            "wrmsr",
            in("ecx") индекс,
            in("eax") lo,
            in("edx") hi,
            options(nomem, nostack, preserves_flags),
        );
    }
}

/// Прочитать LAPIC-регистр.
pub fn читать(смещение: u32) -> u32 {
    let база = БАЗА.load(Ordering::Acquire);
    if база == 0 {
        return 0;
    }
    unsafe { core::ptr::read_volatile((база + смещение as u64) as *const u32) }
}

/// Записать LAPIC-регистр.
pub fn записать(смещение: u32, значение: u32) {
    let база = БАЗА.load(Ordering::Acquire);
    if база == 0 {
        return;
    }
    unsafe { core::ptr::write_volatile((база + смещение as u64) as *mut u32, значение) }
}

/// Включён ли APIC-режим (вместо 8259 PIC).
pub fn активен() -> bool {
    ИНИЦИАЛИЗИРОВАН.load(Ordering::Acquire)
}

/// Адрес LAPIC (физический, 0 если не инициализирован).
pub fn база() -> u64 {
    БАЗА.load(Ordering::Acquire)
}

/// Идентификатор APIC текущего CPU (после инициализации).
pub fn ид() -> u32 {
    if !активен() {
        return 0;
    }
    читать(РЕГ_ID) >> 24
}

/// Полностью замаскировать оба 8259 PIC (требуется при переходе на APIC).
unsafe fn замаскировать_8259() {
    unsafe {
        // OCW1 — write 0xFF to data ports.
        asm!("out dx, al", in("dx") 0xA1u16, in("al") 0xFFu8, options(nomem, nostack));
        asm!("out dx, al", in("dx") 0x21u16, in("al") 0xFFu8, options(nomem, nostack));
    }
}

/// Инициализировать Local APIC текущего ядра.
///
/// `адрес_лапик` обычно 0xFEE00000 (берётся из ACPI MADT).
///
/// Шаги:
///   1) Маскируем 8259 PIC полностью.
///   2) Включаем глобально через MSR IA32_APIC_BASE (бит 11).
///   3) Очищаем TPR (нулевой приоритет — пропускаем все прерывания).
///   4) Задаём «безопасные» LVT: timer/LINT0/LINT1 маскированы.
///   5) Включаем через SVR с вектором 0xFF («spurious»).
///
/// # Безопасность
/// Должно вызываться один раз при старте ядра. Адрес должен быть валидным
/// MMIO-окном LAPIC. У нас он покрыт identity-mapping.
pub unsafe fn инициализировать(адрес_лапик: u64) {
    БАЗА.store(адрес_лапик, Ordering::Release);

    // 1) Маскируем legacy PIC, иначе оба контроллера будут гонять прерывания.
    unsafe { замаскировать_8259() };

    // 2) Глобально включаем APIC через MSR.
    unsafe {
        let base = rdmsr(MSR_APIC_BASE);
        wrmsr(MSR_APIC_BASE, base | APIC_BASE_BIT_GLOBAL_ENABLE);
    }

    // 3) Сбрасываем TPR — принимаем все прерывания.
    записать(РЕГ_TPR, 0);

    // 4) Маскируем все локальные источники: таймер, LINT0/1, error.
    let маска: u32 = 1 << 16;
    записать(РЕГ_LVT_TIMER, маска);
    записать(РЕГ_LVT_LINT0, маска);
    записать(РЕГ_LVT_LINT1, маска);
    записать(РЕГ_LVT_ERROR, маска);

    // 5) Включаем приём прерываний через SVR.
    записать(РЕГ_SVR, SVR_БИТ_РАЗРЕШЕНИЯ | 0xFF);

    ИНИЦИАЛИЗИРОВАН.store(true, Ordering::Release);
}

/// Подтверждение прерывания (End-Of-Interrupt). Вызывается из IRQ-трамплина.
/// В отличие от 8259 PIC, для LAPIC EOI — это просто запись 0 в РЕГ_EOI.
#[inline(always)]
pub fn подтвердить() {
    записать(РЕГ_EOI, 0);
}
