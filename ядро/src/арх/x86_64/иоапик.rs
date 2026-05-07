// ДубинаОС
// Авторские права (C) 2026, Команда ДубинаОС
// Лицензия: GPL-3.0-or-later
//
// I/O APIC — маршрутизатор прерываний от устройств в Local APIC.
//
// На современных машинах (включая ASUS X552E) это замена 8259 PIC.
// Каждая запись «redirection table» описывает, как один GSI (Global
// System Interrupt) попадает в LAPIC: вектор, режим, маска и так далее.
//
// Регистры IO APIC доступны через два MMIO-регистра:
//   IOREGSEL @ база + 0x00 — индекс регистра
//   IOWIN    @ база + 0x10 — данные
//
// Регистры:
//   0x00 IOAPICID
//   0x01 IOAPICVER  (lower 8 = version, bits 16..23 = max redirection entry)
//   0x10..  redirection table — каждая запись 2 dword (low + high)

#![allow(dead_code)]

use core::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use spin::Mutex;

const ИНДЕКС_ID: u32 = 0x00;
const ИНДЕКС_ВЕРСИЯ: u32 = 0x01;
const ИНДЕКС_АРБ: u32 = 0x02;
const ИНДЕКС_РЕД_БАЗА: u32 = 0x10; // дальше: 0x10 + 2*N

const IOAPIC_REGSEL: u32 = 0x00;
const IOAPIC_REGWIN: u32 = 0x10;

/// Флаги polarity / trigger из ACPI MADT ISO.
pub const POLARITY_HIGH: u16 = 0b01;
pub const POLARITY_LOW: u16 = 0b11;
pub const TRIGGER_EDGE: u16 = 0b01 << 2;
pub const TRIGGER_LEVEL: u16 = 0b11 << 2;

/// Запись в redirection table (64 бит, низкий и высокий dword).
#[derive(Debug, Clone, Copy)]
pub struct ЗаписьМаршрутизации {
    pub вектор: u8,
    pub режим_доставки: u8,    // 0 = fixed
    pub режим_назначения: u8,   // 0 = physical
    pub статус: u8,            // 0=idle
    pub полярность: u8,        // 0 = active high
    pub удалённый: u8,         // remote IRR
    pub триггер: u8,           // 0 = edge, 1 = level
    pub маска: bool,           // true = выключено
    pub apic_id: u8,           // куда посылать (для physical: APIC ID)
}

impl ЗаписьМаршрутизации {
    pub const fn маскированная() -> Self {
        Self {
            вектор: 0,
            режим_доставки: 0,
            режим_назначения: 0,
            статус: 0,
            полярность: 0,
            удалённый: 0,
            триггер: 0,
            маска: true,
            apic_id: 0,
        }
    }

    /// Простой fixed-mode маршрут на CPU `apic_id` с вектором `вектор`.
    pub const fn простая(вектор: u8, apic_id: u8) -> Self {
        Self {
            вектор,
            режим_доставки: 0,
            режим_назначения: 0,
            статус: 0,
            полярность: 0,
            удалённый: 0,
            триггер: 0,
            маска: false,
            apic_id,
        }
    }

    /// Преобразовать в (low, high) dword по формату IOAPIC redirection entry.
    fn в_слова(&self) -> (u32, u32) {
        let mut низ: u32 = self.вектор as u32;
        низ |= (self.режим_доставки as u32 & 0x7) << 8;
        низ |= (self.режим_назначения as u32 & 0x1) << 11;
        низ |= (self.полярность as u32 & 0x1) << 13;
        низ |= (self.триггер as u32 & 0x1) << 15;
        if self.маска {
            низ |= 1 << 16;
        }
        let верх = (self.apic_id as u32) << 24;
        (низ, верх)
    }
}

static БАЗА: AtomicU64 = AtomicU64::new(0);
static GSI_БАЗА: AtomicU64 = AtomicU64::new(0);
static MAX_RED: AtomicU64 = AtomicU64::new(0);
static ЗАМОК: Mutex<()> = Mutex::new(());
static ИНИЦИАЛИЗИРОВАН: AtomicBool = AtomicBool::new(false);

pub fn активен() -> bool {
    ИНИЦИАЛИЗИРОВАН.load(Ordering::Acquire)
}

pub fn база() -> u64 {
    БАЗА.load(Ordering::Acquire)
}

unsafe fn читать_рег(индекс: u32) -> u32 {
    let база = БАЗА.load(Ordering::Acquire);
    unsafe {
        core::ptr::write_volatile((база + IOAPIC_REGSEL as u64) as *mut u32, индекс);
        core::ptr::read_volatile((база + IOAPIC_REGWIN as u64) as *const u32)
    }
}

unsafe fn записать_рег(индекс: u32, значение: u32) {
    let база = БАЗА.load(Ordering::Acquire);
    unsafe {
        core::ptr::write_volatile((база + IOAPIC_REGSEL as u64) as *mut u32, индекс);
        core::ptr::write_volatile((база + IOAPIC_REGWIN as u64) as *mut u32, значение);
    }
}

/// Инициализировать IO APIC.
///
/// `адрес` — физический адрес MMIO-окна (обычно 0xFEC00000).
/// `gsi_база` — индекс GSI первой записи (обычно 0).
///
/// Маскируем все redirection entries.
pub unsafe fn инициализировать(адрес: u32, gsi_база: u32) {
    БАЗА.store(адрес as u64, Ordering::Release);
    GSI_БАЗА.store(gsi_база as u64, Ordering::Release);

    // Сначала прочитаем версию, чтобы узнать число entries.
    let версия = unsafe { читать_рег(ИНДЕКС_ВЕРСИЯ) };
    let макс_ред = ((версия >> 16) & 0xFF) as u64; // 0..N
    MAX_RED.store(макс_ред, Ordering::Release);

    // Маскируем все entries.
    let _g = ЗАМОК.lock();
    for i in 0..=макс_ред {
        let индекс = ИНДЕКС_РЕД_БАЗА + (i as u32 * 2);
        unsafe {
            записать_рег(индекс, 1 << 16); // маска
            записать_рег(индекс + 1, 0);
        }
    }
    drop(_g);

    ИНИЦИАЛИЗИРОВАН.store(true, Ordering::Release);
}

/// Запрограммировать одну запись маршрутизации (по GSI-номеру относительно
/// этого IO APIC).
pub fn задать_маршрут(gsi: u32, запись: ЗаписьМаршрутизации) {
    if !активен() {
        return;
    }
    let макс_ред = MAX_RED.load(Ordering::Acquire);
    if gsi as u64 > макс_ред {
        return;
    }
    let (низ, верх) = запись.в_слова();
    let индекс = ИНДЕКС_РЕД_БАЗА + (gsi * 2);
    let _g = ЗАМОК.lock();
    unsafe {
        // Сначала high (apic id), потом low — чтобы между записями не было
        // запуска прерывания со старым target.
        записать_рег(индекс + 1, верх);
        записать_рег(индекс, низ);
    }
}

/// Замаскировать конкретный GSI.
pub fn маскировать(gsi: u32) {
    if !активен() {
        return;
    }
    let индекс = ИНДЕКС_РЕД_БАЗА + (gsi * 2);
    let _g = ЗАМОК.lock();
    unsafe {
        let текущее = читать_рег(индекс);
        записать_рег(индекс, текущее | (1 << 16));
    }
}

/// Снять маску с конкретного GSI.
pub fn снять_маску(gsi: u32) {
    if !активен() {
        return;
    }
    let индекс = ИНДЕКС_РЕД_БАЗА + (gsi * 2);
    let _g = ЗАМОК.lock();
    unsafe {
        let текущее = читать_рег(индекс);
        записать_рег(индекс, текущее & !(1 << 16));
    }
}

/// Версия IO APIC (мини-инфо для диагностики).
pub fn версия_максимум_записей() -> (u8, u8) {
    if !активен() {
        return (0, 0);
    }
    let версия = unsafe { читать_рег(ИНДЕКС_ВЕРСИЯ) };
    (версия as u8, ((версия >> 16) & 0xFF) as u8)
}
