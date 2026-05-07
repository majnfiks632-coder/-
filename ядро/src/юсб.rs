// ДубинаОС
// Авторские права (C) 2026, Команда ДубинаОС
// Лицензия: GPL-3.0-or-later
//
// USB-стек ДубинаОС, этап 3.3.
//
// Задача этого модуля — обнаружить и опросить хост-контроллеры USB,
// которые есть на современных ПК (включая ASUS X552E):
//
//   * EHCI (USB 2.0)  — class 0x0C, subclass 0x03, prog_if 0x20
//   * XHCI (USB 3.0+) — class 0x0C, subclass 0x03, prog_if 0x30
//
// EHCI — основной хост на X552E (там нет XHCI на старых APU). XHCI часто
// инициализирован прошивкой в BIOS Legacy Support и его нужно отбирать
// через USBLEGSUP capability.
//
// На этом этапе мы не делаем полный USB-enumeration (descriptor reads,
// SetAddress, configuration) — это огромный кусок работы. Ограничиваемся:
//   * чтением CAPLENGTH/HCIVERSION/HCSPARAMS;
//   * подсчётом физических портов;
//   * сбросом контроллера и опросом PORTSC — кто подключен.
//
// HID Boot Protocol (клавиатура/мышь поверх USB) — следующая итерация.

#![allow(dead_code)]

use crate::println;
use crate::писиай;

// ==========================================================================
// EHCI capability registers (offset от BAR0).
// ==========================================================================

const EHCI_CAPLENGTH: u64 = 0x00; // u8: длина capability-блока
const EHCI_HCIVERSION: u64 = 0x02; // u16: BCD версия
const EHCI_HCSPARAMS: u64 = 0x04;  // u32: structural параметры
const EHCI_HCCPARAMS: u64 = 0x08;  // u32: capability-параметры

// Operational registers (offset от BAR0 + CAPLENGTH).
const EHCI_USBCMD: u64 = 0x00;
const EHCI_USBSTS: u64 = 0x04;
const EHCI_USBINTR: u64 = 0x08;
const EHCI_FRINDEX: u64 = 0x0C;
const EHCI_CTRLDSSEGMENT: u64 = 0x10;
const EHCI_PERIODICLISTBASE: u64 = 0x14;
const EHCI_ASYNCLISTADDR: u64 = 0x18;
const EHCI_CONFIGFLAG: u64 = 0x40;
const EHCI_PORTSC_BASE: u64 = 0x44; // массив, по 4 байта на порт

const USBCMD_RUN: u32 = 1 << 0;
const USBCMD_HCRESET: u32 = 1 << 1;
const USBSTS_HALTED: u32 = 1 << 12;
const PORTSC_CCS: u32 = 1 << 0; // Connect Status

#[derive(Debug, Clone, Copy)]
pub struct ИнфоEHCI {
    pub база: u64,
    pub адрес_pci: писиай::АдресPCI,
    pub версия: u16,
    pub количество_портов: u8,
    pub количество_подключённых: u8,
    pub op_offset: u32,
}

unsafe fn r8(адрес: u64) -> u8 { unsafe { core::ptr::read_volatile(адрес as *const u8) } }
unsafe fn r16(адрес: u64) -> u16 { unsafe { core::ptr::read_volatile(адрес as *const u16) } }
unsafe fn r32(адрес: u64) -> u32 { unsafe { core::ptr::read_volatile(адрес as *const u32) } }
unsafe fn w32(адрес: u64, значение: u32) {
    unsafe { core::ptr::write_volatile(адрес as *mut u32, значение) }
}

/// Найти EHCI на PCI.
pub fn найти_ehci() -> Option<писиай::ИнфоPCI> {
    писиай::найти_с_progif(0x0C, 0x03, 0x20)
}

/// Найти XHCI на PCI.
pub fn найти_xhci() -> Option<писиай::ИнфоPCI> {
    писиай::найти_с_progif(0x0C, 0x03, 0x30)
}

/// Поднять EHCI: сбросить контроллер, опросить порты.
pub fn инициализировать_ehci() -> Option<ИнфоEHCI> {
    let pci = найти_ehci()?;
    println!(
        "[ИНФО] EHCI: найден контроллер {:04X}:{:04X} в {:02X}:{:02X}.{}",
        pci.vendor, pci.device, pci.адрес.шина, pci.адрес.устройство, pci.адрес.функция
    );
    let база = писиай::прочитать_bar(pci.адрес, 0);
    if база == 0 {
        println!("[ВНИМАНИЕ] EHCI: BAR0 == 0");
        return None;
    }
    if база >= 0x1_0000_0000 {
        println!("[ВНИМАНИЕ] EHCI: BAR0 выше 4 ГиБ — пропускаем");
        return None;
    }
    println!("[ИНФО] EHCI: MMIO @ 0x{:X}", база);

    писиай::включить_bus_master(pci.адрес);

    // Прочитать capability registers.
    let caplength = unsafe { r8(база + EHCI_CAPLENGTH) } as u32;
    let версия = unsafe { r16(база + EHCI_HCIVERSION) };
    let hcsparams = unsafe { r32(база + EHCI_HCSPARAMS) };
    let n_ports = (hcsparams & 0xF) as u8;

    println!(
        "[ИНФО] EHCI: версия 0x{:04X}, op_offset {}, портов {}, HCSPARAMS 0x{:08X}",
        версия, caplength, n_ports, hcsparams
    );

    let op_base = база + caplength as u64;

    // Сбросить контроллер.
    let cmd = unsafe { r32(op_base + EHCI_USBCMD) };
    unsafe { w32(op_base + EHCI_USBCMD, (cmd & !USBCMD_RUN) | USBCMD_HCRESET) };
    let mut попыток = 0u32;
    while unsafe { r32(op_base + EHCI_USBCMD) } & USBCMD_HCRESET != 0 {
        попыток += 1;
        if попыток > 100_000 {
            println!("[ВНИМАНИЕ] EHCI: HCRESET не сбросился (продолжаем)");
            break;
        }
    }

    // Включить configured-flag, чтобы порты принадлежали EHCI, а не companion.
    unsafe { w32(op_base + EHCI_CONFIGFLAG, 1) };

    // Опросить порты.
    let mut подключено = 0u8;
    for i in 0..n_ports {
        let ps = unsafe { r32(op_base + EHCI_PORTSC_BASE + (i as u64) * 4) };
        let connected = ps & PORTSC_CCS != 0;
        if connected {
            подключено += 1;
            println!("[OK]   EHCI порт {}: устройство ПОДКЛЮЧЕНО (PORTSC=0x{:08X})", i, ps);
        } else {
            println!("[ИНФО] EHCI порт {}: пусто (PORTSC=0x{:08X})", i, ps);
        }
    }

    Some(ИнфоEHCI {
        база,
        адрес_pci: pci.адрес,
        версия,
        количество_портов: n_ports,
        количество_подключённых: подключено,
        op_offset: caplength,
    })
}

// ==========================================================================
// XHCI capability registers
// ==========================================================================

const XHCI_CAPLENGTH: u64 = 0x00;
const XHCI_HCIVERSION: u64 = 0x02;
const XHCI_HCSPARAMS1: u64 = 0x04;
const XHCI_HCSPARAMS2: u64 = 0x08;
const XHCI_HCSPARAMS3: u64 = 0x0C;
const XHCI_HCCPARAMS1: u64 = 0x10;
const XHCI_DBOFF: u64 = 0x14;
const XHCI_RTSOFF: u64 = 0x18;

#[derive(Debug, Clone, Copy)]
pub struct ИнфоXHCI {
    pub база: u64,
    pub адрес_pci: писиай::АдресPCI,
    pub версия: u16,
    pub max_slots: u8,
    pub max_ports: u8,
    pub max_intrs: u16,
}

/// Поднять XHCI: пока — только идентификация. Полная инициализация
/// (DCBAA/Command Ring/Event Ring) — следующий этап.
pub fn инициализировать_xhci() -> Option<ИнфоXHCI> {
    let pci = найти_xhci()?;
    println!(
        "[ИНФО] XHCI: найден контроллер {:04X}:{:04X} в {:02X}:{:02X}.{}",
        pci.vendor, pci.device, pci.адрес.шина, pci.адрес.устройство, pci.адрес.функция
    );
    let база = писиай::прочитать_bar(pci.адрес, 0);
    if база == 0 {
        println!("[ВНИМАНИЕ] XHCI: BAR0 == 0");
        return None;
    }
    println!("[ИНФО] XHCI: MMIO @ 0x{:X}", база);
    if база >= 0x1_0000_0000 {
        println!(
            "[ВНИМАНИЕ] XHCI: BAR0 за пределами identity-mapping 4 ГиБ — \
             регистрация только в логе, инициализация будет в этапе 3.4"
        );
        return Some(ИнфоXHCI {
            база,
            адрес_pci: pci.адрес,
            версия: 0,
            max_slots: 0,
            max_ports: 0,
            max_intrs: 0,
        });
    }

    писиай::включить_bus_master(pci.адрес);

    let версия = unsafe { r16(база + XHCI_HCIVERSION) };
    let p1 = unsafe { r32(база + XHCI_HCSPARAMS1) };
    let p2 = unsafe { r32(база + XHCI_HCSPARAMS2) };
    let max_slots = (p1 & 0xFF) as u8;
    let max_intrs = ((p1 >> 8) & 0x7FF) as u16;
    let max_ports = ((p1 >> 24) & 0xFF) as u8;
    let _ = p2;

    println!(
        "[ИНФО] XHCI: версия 0x{:04X}, слотов до {}, портов {}, прерываний {}",
        версия, max_slots, max_ports, max_intrs
    );

    Some(ИнфоXHCI {
        база,
        адрес_pci: pci.адрес,
        версия,
        max_slots,
        max_ports,
        max_intrs,
    })
}

/// Точка входа: пробуем XHCI и EHCI (XHCI приоритетнее на новых машинах,
/// но на ASUS X552E его нет — там только EHCI).
pub fn инициализировать() {
    let mut нашли = false;
    if let Some(_) = инициализировать_xhci() {
        нашли = true;
    }
    if let Some(_) = инициализировать_ehci() {
        нашли = true;
    }
    if !нашли {
        println!("[ИНФО] USB: контроллеры USB не найдены на шине PCI");
    }
}
