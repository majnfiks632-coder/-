// ДубинаОС
// Авторские права (C) 2026, Команда ДубинаОС
// Лицензия: GPL-3.0-or-later
//
// Перечисление и доступ к конфигурационному пространству PCI/PCIe.
//
// Поддерживаются два механизма доступа:
//
//  * **PCIe ECAM** — Memory-mapped Enhanced Configuration Access Mechanism.
//    Адрес базы берётся из MCFG (через `crate::акпи`). На современных машинах
//    (включая ASUS X552E с включённым PCIe) это основной способ.
//    Преимущества: даёт доступ к 4 КиБ конфигурации устройства (vs 256 байт),
//    нужен для PCIe-расширений (MSI-X capability и т.п.).
//
//  * **Legacy 0xCF8/0xCFC I/O ports** — старая «PCI Configuration Mechanism #1».
//    Доступны 256 байт конфигурации. На QEMU `-machine pc` MCFG отсутствует
//    и работает только этот механизм. На реальном железе работает почти всегда
//    как фолбэк.
//
// Мы выбираем механизм на основе наличия таблицы MCFG.

#![allow(dead_code)]

use crate::println;
use core::sync::atomic::{AtomicBool, AtomicU64, Ordering};

// ==========================================================================
// Адресация
// ==========================================================================

/// «Адрес» устройства PCI: сегмент:шина:устройство:функция.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct АдресPCI {
    pub сегмент: u16,
    pub шина: u8,
    pub устройство: u8,
    pub функция: u8,
}

impl АдресPCI {
    pub const fn новый(сегмент: u16, шина: u8, устройство: u8, функция: u8) -> Self {
        Self { сегмент, шина, устройство, функция }
    }
}

// ==========================================================================
// Конфигурационная информация
// ==========================================================================

/// Заголовок Type 0/1: общий для всех устройств первые 16 байт.
#[derive(Debug, Clone, Copy)]
pub struct ИнфоPCI {
    pub адрес: АдресPCI,
    pub vendor: u16,
    pub device: u16,
    pub command: u16,
    pub status: u16,
    pub revision: u8,
    pub prog_if: u8,
    pub subclass: u8,
    pub class: u8,
    pub cache_line_size: u8,
    pub latency_timer: u8,
    pub header_type: u8, // bit 7 = multi-function
    pub bist: u8,
}

impl ИнфоPCI {
    pub fn многофункциональное(&self) -> bool {
        self.header_type & 0x80 != 0
    }

    pub fn заголовочный_тип(&self) -> u8 {
        self.header_type & 0x7F
    }

    /// Человекочитаемая категория: «Mass Storage Controller», «USB Controller», ...
    pub fn описание_класса(&self) -> &'static str {
        описание_класса(self.class, self.subclass, self.prog_if)
    }
}

// ==========================================================================
// Глобальное состояние
// ==========================================================================

/// База ECAM (если есть). 0 = нет; используем legacy 0xCF8/0xCFC.
static БАЗА_ECAM: AtomicU64 = AtomicU64::new(0);
static ECAM_ШИНА_КОНЕЦ: spin::Mutex<u8> = spin::Mutex::new(0);
static ИНИЦИАЛИЗИРОВАН: AtomicBool = AtomicBool::new(false);

/// Установить базу ECAM (вызывается из `главный.rs` после разбора ACPI).
pub fn использовать_ecam(база: u64, шина_конец: u8) {
    БАЗА_ECAM.store(база, Ordering::Release);
    *ECAM_ШИНА_КОНЕЦ.lock() = шина_конец;
    ИНИЦИАЛИЗИРОВАН.store(true, Ordering::Release);
}

/// Установить, что ECAM нет — будем использовать legacy CF8/CFC.
pub fn использовать_legacy() {
    БАЗА_ECAM.store(0, Ordering::Release);
    ИНИЦИАЛИЗИРОВАН.store(true, Ordering::Release);
}

pub fn ecam_доступен() -> bool {
    БАЗА_ECAM.load(Ordering::Acquire) != 0
}

// ==========================================================================
// Чтение конфигурационного пространства
// ==========================================================================

/// Прочитать слово (32 бита) конфигурационного пространства PCI.
///
/// `смещение` должно быть выровнено на 4 байта.
///
/// # Безопасность
/// На реальной машине I/O-порты 0xCF8/0xCFC требуют ring 0; в наше ядро это
/// гарантируется. ECAM-доступ зависит от того, что MMIO-окно отображено
/// (у нас identity-mapping 0..4 ГиБ покрывает типичные ECAM ниже 4 ГиБ).
pub fn читать_слово(адрес: АдресPCI, смещение: u16) -> u32 {
    let база = БАЗА_ECAM.load(Ordering::Acquire);
    if база != 0 && смещение < 4096 {
        return unsafe { читать_ecam(база, адрес, смещение) };
    }
    if смещение < 256 {
        return unsafe { читать_legacy(адрес, смещение as u8) };
    }
    0xFFFF_FFFF
}

/// Записать слово (32 бита) конфигурационного пространства PCI.
pub fn записать_слово(адрес: АдресPCI, смещение: u16, значение: u32) {
    let база = БАЗА_ECAM.load(Ordering::Acquire);
    if база != 0 && смещение < 4096 {
        unsafe { записать_ecam(база, адрес, смещение, значение) };
        return;
    }
    if смещение < 256 {
        unsafe { записать_legacy(адрес, смещение as u8, значение) };
    }
}

unsafe fn адрес_ecam(база: u64, адрес: АдресPCI, смещение: u16) -> *mut u32 {
    // Формула ECAM: база + (шина << 20) | (устройство << 15) | (функция << 12) | смещение
    let off = (адрес.шина as u64) << 20
        | (адрес.устройство as u64 & 0x1F) << 15
        | (адрес.функция as u64 & 0x07) << 12
        | (смещение as u64 & 0xFFC);
    (база + off) as *mut u32
}

unsafe fn читать_ecam(база: u64, адрес: АдресPCI, смещение: u16) -> u32 {
    unsafe { core::ptr::read_volatile(адрес_ecam(база, адрес, смещение)) }
}

unsafe fn записать_ecam(база: u64, адрес: АдресPCI, смещение: u16, значение: u32) {
    unsafe { core::ptr::write_volatile(адрес_ecam(база, адрес, смещение), значение) }
}

#[cfg(target_arch = "x86_64")]
unsafe fn читать_legacy(адрес: АдресPCI, смещение: u8) -> u32 {
    // CF8 формат:
    //   bit 31    : enable (=1)
    //   bits 30..24: reserved
    //   bits 23..16: шина
    //   bits 15..11: устройство
    //   bits 10..8 : функция
    //   bits 7..0  : смещение (биты 1..0 = 0)
    let cmd: u32 = 0x8000_0000
        | ((адрес.шина as u32) << 16)
        | (((адрес.устройство as u32) & 0x1F) << 11)
        | (((адрес.функция as u32) & 0x07) << 8)
        | (смещение as u32 & 0xFC);
    let значение: u32;
    unsafe {
        core::arch::asm!(
            "out dx, eax",
            in("dx") 0xCF8u16,
            in("eax") cmd,
            options(nomem, nostack, preserves_flags)
        );
        core::arch::asm!(
            "in eax, dx",
            in("dx") 0xCFCu16,
            out("eax") значение,
            options(nomem, nostack, preserves_flags)
        );
    }
    значение
}

#[cfg(target_arch = "x86_64")]
unsafe fn записать_legacy(адрес: АдресPCI, смещение: u8, значение: u32) {
    let cmd: u32 = 0x8000_0000
        | ((адрес.шина as u32) << 16)
        | (((адрес.устройство as u32) & 0x1F) << 11)
        | (((адрес.функция as u32) & 0x07) << 8)
        | (смещение as u32 & 0xFC);
    unsafe {
        core::arch::asm!(
            "out dx, eax",
            in("dx") 0xCF8u16,
            in("eax") cmd,
            options(nomem, nostack, preserves_flags)
        );
        core::arch::asm!(
            "out dx, eax",
            in("dx") 0xCFCu16,
            in("eax") значение,
            options(nomem, nostack, preserves_flags)
        );
    }
}

#[cfg(not(target_arch = "x86_64"))]
unsafe fn читать_legacy(_адрес: АдресPCI, _смещение: u8) -> u32 {
    0xFFFF_FFFF
}
#[cfg(not(target_arch = "x86_64"))]
unsafe fn записать_legacy(_адрес: АдресPCI, _смещение: u8, _значение: u32) {}

// ==========================================================================
// Высокоуровневые помощники
// ==========================================================================

/// Прочитать «шапку» устройства. Возвращает `None`, если устройства нет
/// (vendor==0xFFFF означает «нет устройства»).
pub fn прочитать_инфо(адрес: АдресPCI) -> Option<ИнфоPCI> {
    let слово0 = читать_слово(адрес, 0x00);
    let vendor = слово0 as u16;
    if vendor == 0xFFFF {
        return None;
    }
    let device = (слово0 >> 16) as u16;
    let слово1 = читать_слово(адрес, 0x04);
    let command = слово1 as u16;
    let status = (слово1 >> 16) as u16;
    let слово2 = читать_слово(адрес, 0x08);
    let revision = слово2 as u8;
    let prog_if = (слово2 >> 8) as u8;
    let subclass = (слово2 >> 16) as u8;
    let class = (слово2 >> 24) as u8;
    let слово3 = читать_слово(адрес, 0x0C);
    let cache_line_size = слово3 as u8;
    let latency_timer = (слово3 >> 8) as u8;
    let header_type = (слово3 >> 16) as u8;
    let bist = (слово3 >> 24) as u8;
    Some(ИнфоPCI {
        адрес,
        vendor,
        device,
        command,
        status,
        revision,
        prog_if,
        subclass,
        class,
        cache_line_size,
        latency_timer,
        header_type,
        bist,
    })
}

/// Перечислить все устройства, следуя PCI-to-PCI мостам.
///
/// Алгоритм:
///   1) Стартуем со шины 0.
///   2) На каждой шине перебираем 32 слота × до 8 функций.
///   3) Если устройство — PCI-PCI bridge (header type 1, class 6, subclass 4),
///      читаем секондари-шину из конфигурации и добавляем её в очередь.
///   4) ECAM ограничивает диапазон шин по `шина_конец` из MCFG; legacy
///      ограничивает 32 шинами (на ASUS X552E и QEMU `pc` этого с запасом).
///
/// Это даёт быстрое перечисление на legacy CF8/CFC (нет 256-кратного
/// холостого опроса) и корректное — на ECAM.
pub fn перечислить(mut выдать: impl FnMut(&ИнфоPCI)) {
    let шина_максимум: u16 = if ecam_доступен() {
        *ECAM_ШИНА_КОНЕЦ.lock() as u16 + 1
    } else {
        32
    };
    let mut посещённые: [bool; 256] = [false; 256];
    // Очередь шин, которые ещё нужно обойти. На X552E и QEMU редко >4.
    let mut очередь: [u8; 64] = [0; 64];
    let mut длина: usize = 1;
    let mut позиция: usize = 0;
    очередь[0] = 0;

    while позиция < длина {
        let шина = очередь[позиция];
        позиция += 1;
        if (шина as u16) >= шина_максимум {
            continue;
        }
        if посещённые[шина as usize] {
            continue;
        }
        посещённые[шина as usize] = true;

        for устр in 0..32u8 {
            // Сначала функция 0; если устройства нет — пропускаем слот целиком.
            let адр0 = АдресPCI::новый(0, шина, устр, 0);
            let Some(инфо0) = прочитать_инфо(адр0) else { continue };
            обработать_устройство(&инфо0, &mut выдать, &mut очередь, &mut длина);
            if !инфо0.многофункциональное() {
                continue;
            }
            for функ in 1..8u8 {
                let адр = АдресPCI::новый(0, шина, устр, функ);
                if let Some(инфо) = прочитать_инфо(адр) {
                    обработать_устройство(&инфо, &mut выдать, &mut очередь, &mut длина);
                }
            }
        }
    }
}

/// Вспомогательная: вызвать пользовательский колбэк и, если устройство —
/// мост, добавить его секондари-шину в очередь.
fn обработать_устройство(
    инфо: &ИнфоPCI,
    выдать: &mut impl FnMut(&ИнфоPCI),
    очередь: &mut [u8; 64],
    длина: &mut usize,
) {
    выдать(инфо);
    // PCI-PCI bridge (header type 1).
    if инфо.заголовочный_тип() == 1 {
        let dword = читать_слово(инфо.адрес, 0x18);
        // dword = primary | (secondary << 8) | (subordinate << 16) | (latency << 24)
        let secondary = (dword >> 8) as u8;
        if secondary != 0 && *длина < очередь.len() {
            очередь[*длина] = secondary;
            *длина += 1;
        }
    }
}

/// Распечатать все найденные устройства в журнал.
pub fn распечатать_список() {
    let mut счётчик: u32 = 0;
    let mut nvme: u32 = 0;
    let mut ahci: u32 = 0;
    let mut xhci: u32 = 0;
    let mut ehci: u32 = 0;
    let mut ethernet: u32 = 0;
    перечислить(|инфо| {
        счётчик += 1;
        // Подсчёт интересных нам устройств для X552E и современных ПК.
        match (инфо.class, инфо.subclass, инфо.prog_if) {
            (0x01, 0x06, 0x01) => ahci += 1,                  // AHCI 1.0
            (0x01, 0x08, _) => nvme += 1,                     // NVMe (subclass = 0x08)
            (0x0C, 0x03, 0x30) => xhci += 1,                  // USB XHCI
            (0x0C, 0x03, 0x20) => ehci += 1,                  // USB EHCI
            (0x02, 0x00, _) => ethernet += 1,                 // Ethernet
            _ => {}
        }
        println!(
            "       {:02X}:{:02X}.{}  {:04X}:{:04X}  {:02X}:{:02X}:{:02X}  {}",
            инфо.адрес.шина, инфо.адрес.устройство, инфо.адрес.функция,
            инфо.vendor, инфо.device,
            инфо.class, инфо.subclass, инфо.prog_if,
            инфо.описание_класса()
        );
    });
    println!(
        "[OK]   PCI устройств всего: {}, AHCI: {}, NVMe: {}, USB XHCI: {}, USB EHCI: {}, Ethernet: {}",
        счётчик, ahci, nvme, xhci, ehci, ethernet
    );
}

/// Найти первое устройство с заданным class/subclass.
pub fn найти(class: u8, subclass: u8) -> Option<ИнфоPCI> {
    let mut результат: Option<ИнфоPCI> = None;
    перечислить(|инфо| {
        if результат.is_none() && инфо.class == class && инфо.subclass == subclass {
            результат = Some(*инфо);
        }
    });
    результат
}

/// Найти первое устройство с заданным class/subclass/prog_if.
pub fn найти_с_progif(class: u8, subclass: u8, prog_if: u8) -> Option<ИнфоPCI> {
    let mut результат: Option<ИнфоPCI> = None;
    перечислить(|инфо| {
        if результат.is_none()
            && инфо.class == class
            && инфо.subclass == subclass
            && инфо.prog_if == prog_if
        {
            результат = Some(*инфо);
        }
    });
    результат
}

/// Прочитать значение BAR (Base Address Register).
///
/// Для 32-битного memory BAR возвращает физический адрес (low 32 бит).
/// Для 64-битного — собирает значение из BAR_N (low) и BAR_N+1 (high).
/// Для I/O BAR возвращает только младшие 32 бит без бита 0.
///
/// `номер_bar` — индекс 0..5 (BAR0..BAR5).
pub fn прочитать_bar(адрес: АдресPCI, номер_bar: u8) -> u64 {
    if номер_bar > 5 {
        return 0;
    }
    let смещение = 0x10u16 + (номер_bar as u16) * 4;
    let низ = читать_слово(адрес, смещение);
    if низ & 0x1 == 0x1 {
        // I/O BAR
        return (низ & 0xFFFF_FFFC) as u64;
    }
    let тип_памяти = (низ >> 1) & 0x3;
    let база = низ & 0xFFFF_FFF0;
    if тип_памяти == 0x2 && номер_bar < 5 {
        // 64-битный BAR: верхняя часть в следующем регистре.
        let верх = читать_слово(адрес, смещение + 4);
        ((верх as u64) << 32) | (база as u64)
    } else {
        база as u64
    }
}

/// Включить bus mastering и memory space access на устройстве.
/// Это нужно перед использованием устройства DMA-стилем (AHCI/EHCI/XHCI/NIC).
pub fn включить_bus_master(адрес: АдресPCI) {
    let cmd = читать_слово(адрес, 0x04);
    let новая_cmd = cmd | 0x0006; // bit 1 = memory space, bit 2 = bus master
    записать_слово(адрес, 0x04, новая_cmd);
}

// ==========================================================================
// Описание классов (минимум — для современных ПК и ASUS X552E)
// ==========================================================================

fn описание_класса(class: u8, subclass: u8, prog_if: u8) -> &'static str {
    match (class, subclass, prog_if) {
        (0x00, 0x00, _) => "Old (не классифицировано)",
        (0x00, 0x01, _) => "Old VGA-совместимое",
        (0x01, 0x00, _) => "SCSI-контроллер",
        (0x01, 0x01, _) => "IDE-контроллер",
        (0x01, 0x05, _) => "ATA-контроллер",
        (0x01, 0x06, 0x00) => "SATA контроллер (vendor-specific)",
        (0x01, 0x06, 0x01) => "SATA контроллер (AHCI 1.0)",
        (0x01, 0x06, 0x02) => "SATA контроллер (Serial Storage Bus)",
        (0x01, 0x07, _) => "SAS-контроллер",
        (0x01, 0x08, 0x01) => "NVMe (NVMHCI)",
        (0x01, 0x08, 0x02) => "NVMe Express",
        (0x01, 0x08, _) => "NVMe (другой)",
        (0x01, 0x80, _) => "Mass Storage (другое)",
        (0x02, 0x00, _) => "Ethernet-контроллер",
        (0x02, 0x80, _) => "Сетевой контроллер (другой)",
        (0x03, 0x00, _) => "VGA-совместимый видеоадаптер",
        (0x03, 0x80, _) => "Видеоадаптер (другой)",
        (0x04, 0x01, _) => "Аудио (multimedia)",
        (0x04, 0x03, _) => "Аудио HD Audio",
        (0x05, _, _) => "Контроллер памяти",
        (0x06, 0x00, _) => "Host bridge",
        (0x06, 0x01, _) => "ISA bridge",
        (0x06, 0x04, _) => "PCI-PCI bridge",
        (0x06, 0x05, _) => "PCMCIA bridge",
        (0x07, 0x00, _) => "Serial controller (UART)",
        (0x07, 0x80, _) => "Communication (другое)",
        (0x08, 0x00, _) => "PIC (Programmable Interrupt Controller)",
        (0x08, 0x05, _) => "SD Host controller",
        (0x09, 0x00, _) => "Клавиатура",
        (0x09, 0x02, _) => "Мышь",
        (0x0B, _, _) => "Процессор",
        (0x0C, 0x00, _) => "FireWire (IEEE 1394)",
        (0x0C, 0x03, 0x00) => "USB UHCI",
        (0x0C, 0x03, 0x10) => "USB OHCI",
        (0x0C, 0x03, 0x20) => "USB EHCI (USB 2.0)",
        (0x0C, 0x03, 0x30) => "USB XHCI (USB 3.0/3.1)",
        (0x0C, 0x03, 0xFE) => "USB Device",
        (0x0C, 0x05, _) => "SMBus",
        (0x0C, 0x06, _) => "InfiniBand",
        (0x0D, _, _) => "Беспроводной контроллер (Wi-Fi/Bluetooth)",
        (0x10, _, _) => "Криптографический ускоритель",
        (0x11, _, _) => "Сигнальный процессор",
        (0xFF, _, _) => "Не назначено",
        _ => "PCI-устройство",
    }
}
