// ДубинаОС
// Авторские права (C) 2026, Команда ДубинаОС
// Лицензия: GPL-3.0-or-later
//
// ACPI — Advanced Configuration and Power Interface (по-нашему «АКПИ»).
//
// Этот модуль читает таблицы ACPI, переданные прошивкой через UEFI Configuration
// Table (RSDP в `ИнфоЗагрузки.rsdp`). Без него ядро не может узнать:
//  * сколько на машине ядер CPU (MADT — Local APIC entries),
//  * адрес IO APIC и переопределения IRQ (MADT — IO APIC, ISO),
//  * базу PCIe ECAM для перечисления устройств (MCFG),
//  * базу HPET для нормального таймера высокого разрешения (HPET).
//
// Парсер минимальный: только чтение, без AML, без \_SB и динамических
// устройств. Этого хватает, чтобы выйти из эпохи 8259 PIC + PIT и увидеть
// SATA/USB на современном ПК (например, ASUS X552E).
//
// Все физические адреса ACPI-таблиц на типичной x86_64-машине лежат ниже
// 4 ГиБ и доступны через нашу identity-mapping 0..4 ГиБ из `вход.s`/`вход_uefi.s`.

#![allow(dead_code)]
#![allow(non_snake_case)]

use crate::println;
use core::mem::size_of;

// ==========================================================================
// Сигнатуры таблиц
// ==========================================================================

/// «RSD PTR » — сигнатура RSDP (8 ASCII-байт, последний — пробел).
pub const СИГ_RSDP: [u8; 8] = *b"RSD PTR ";
pub const СИГ_RSDT: [u8; 4] = *b"RSDT";
pub const СИГ_XSDT: [u8; 4] = *b"XSDT";
pub const СИГ_FADT: [u8; 4] = *b"FACP";
pub const СИГ_MADT: [u8; 4] = *b"APIC";
pub const СИГ_MCFG: [u8; 4] = *b"MCFG";
pub const СИГ_HPET: [u8; 4] = *b"HPET";
pub const СИГ_DSDT: [u8; 4] = *b"DSDT";
pub const СИГ_SSDT: [u8; 4] = *b"SSDT";

// ==========================================================================
// Структуры заголовков
// ==========================================================================

/// RSDP — Root System Description Pointer (ACPI 1.0, 20 байт).
#[repr(C, packed)]
#[derive(Debug, Clone, Copy)]
pub struct Rsdp10 {
    pub сигнатура: [u8; 8],   // "RSD PTR "
    pub контрольная: u8,
    pub oem_id: [u8; 6],
    pub ревизия: u8,           // 0 = ACPI 1.0; >=2 = ACPI 2.0+
    pub адрес_rsdt: u32,
}

/// RSDP — расширение ACPI 2.0+ (всего 36 байт).
#[repr(C, packed)]
#[derive(Debug, Clone, Copy)]
pub struct Rsdp20 {
    pub база: Rsdp10,
    pub длина: u32,
    pub адрес_xsdt: u64,
    pub расш_контрольная: u8,
    pub резерв: [u8; 3],
}

/// Заголовок System Description Table (общий для XSDT, RSDT, FADT, MADT, …).
#[repr(C, packed)]
#[derive(Debug, Clone, Copy)]
pub struct ЗаголовокСДТ {
    pub сигнатура: [u8; 4],
    pub длина: u32,             // полная длина включая заголовок
    pub ревизия: u8,
    pub контрольная: u8,
    pub oem_id: [u8; 6],
    pub oem_id_таблицы: [u8; 8],
    pub oem_ревизия: u32,
    pub creator_id: u32,
    pub creator_ревизия: u32,
}

const ЗАГОЛОВОК_РАЗМЕР: usize = size_of::<ЗаголовокСДТ>(); // 36

// ==========================================================================
// MADT — Multiple APIC Description Table
// ==========================================================================

/// Заголовок MADT после общего заголовка SDT: 8 байт.
#[repr(C, packed)]
#[derive(Debug, Clone, Copy)]
pub struct ЗаголовокMADT {
    pub адрес_лапик: u32,    // 32-битный адрес Local APIC по умолчанию
    pub флаги: u32,           // bit 0: PCAT_COMPAT (есть legacy 8259 PIC)
}

/// Тип записи MADT.
pub const MADT_LAPIC: u8 = 0;          // Processor Local APIC
pub const MADT_IOAPIC: u8 = 1;         // I/O APIC
pub const MADT_ISO: u8 = 2;            // Interrupt Source Override
pub const MADT_NMI: u8 = 3;            // NMI Source
pub const MADT_LAPIC_NMI: u8 = 4;      // Local APIC NMI
pub const MADT_LAPIC_OVR: u8 = 5;      // Local APIC Address Override (64-bit)
pub const MADT_X2APIC: u8 = 9;         // Processor Local x2APIC

/// Запись «Processor Local APIC» (тип 0).
#[repr(C, packed)]
#[derive(Debug, Clone, Copy)]
pub struct MadtLapic {
    pub тип: u8,
    pub длина: u8,
    pub acpi_proc_id: u8,
    pub apic_id: u8,
    pub флаги: u32,    // bit 0: enabled, bit 1: online-capable
}

/// Запись «I/O APIC» (тип 1).
#[repr(C, packed)]
#[derive(Debug, Clone, Copy)]
pub struct MadtIoapic {
    pub тип: u8,
    pub длина: u8,
    pub id: u8,
    pub резерв: u8,
    pub адрес: u32,
    pub база_gsi: u32,
}

/// Запись «Interrupt Source Override» (тип 2) — связь legacy IRQ → GSI.
#[repr(C, packed)]
#[derive(Debug, Clone, Copy)]
pub struct MadtIso {
    pub тип: u8,
    pub длина: u8,
    pub шина: u8,           // всегда 0 (ISA)
    pub источник: u8,        // legacy IRQ, например 0 для PIT
    pub gsi: u32,            // соответствующий Global System Interrupt
    pub флаги: u16,          // polarity / trigger
}

/// Запись «Local APIC Address Override» (тип 5).
#[repr(C, packed)]
#[derive(Debug, Clone, Copy)]
pub struct MadtLapicOvr {
    pub тип: u8,
    pub длина: u8,
    pub резерв: u16,
    pub адрес: u64,
}

/// Запись «Processor Local x2APIC» (тип 9).
#[repr(C, packed)]
#[derive(Debug, Clone, Copy)]
pub struct MadtX2apic {
    pub тип: u8,
    pub длина: u8,
    pub резерв: u16,
    pub x2apic_id: u32,
    pub флаги: u32,
    pub acpi_proc_uid: u32,
}

// ==========================================================================
// MCFG — PCIe ECAM
// ==========================================================================

/// Запись MCFG: одно «окно» ECAM на PCI-сегмент.
#[repr(C, packed)]
#[derive(Debug, Clone, Copy)]
pub struct McfgЗапись {
    pub база_ecam: u64,      // физический адрес ECAM-окна
    pub сегмент: u16,
    pub шина_начало: u8,
    pub шина_конец: u8,
    pub резерв: u32,
}

// ==========================================================================
// HPET
// ==========================================================================

/// «Generic Address Structure» — описывает адрес устройства.
#[repr(C, packed)]
#[derive(Debug, Clone, Copy)]
pub struct ОбщийАдрес {
    pub пространство: u8,      // 0=memory, 1=I/O port
    pub разрядность: u8,        // bit width
    pub смещение_бит: u8,
    pub доступ: u8,
    pub адрес: u64,
}

/// Заголовок HPET (после общего SDT).
#[repr(C, packed)]
#[derive(Debug, Clone, Copy)]
pub struct ЗаголовокHPET {
    pub event_timer_block_id: u32,
    pub база: ОбщийАдрес,
    pub номер_hpet: u8,
    pub минимальный_тик: u16,
    pub защита_страниц: u8,
}

// ==========================================================================
// Главный объект описания
// ==========================================================================

/// Сводка ACPI-таблиц, найденных при загрузке.
///
/// Адреса — физические. На x86_64 они доступны напрямую через identity-mapping.
#[derive(Debug, Clone, Copy, Default)]
pub struct АкпиТаблицы {
    pub ревизия_rsdp: u8,
    pub xsdt: Option<u64>,      // физ. адрес XSDT (ACPI 2.0+)
    pub rsdt: Option<u64>,      // физ. адрес RSDT (ACPI 1.0)
    pub fadt: Option<u64>,
    pub madt: Option<u64>,
    pub mcfg: Option<u64>,
    pub hpet: Option<u64>,

    /// Сводная статистика, заполняется при разборе MADT.
    pub количество_lapic: u32,
    pub количество_x2apic: u32,
    pub количество_ioapic: u32,
    pub адрес_lapic: u64,        // адрес Local APIC (с учётом override)
    pub legacy_pic: bool,        // флаг PCAT_COMPAT из MADT
}

impl АкпиТаблицы {
    pub const fn пустая() -> Self {
        Self {
            ревизия_rsdp: 0,
            xsdt: None,
            rsdt: None,
            fadt: None,
            madt: None,
            mcfg: None,
            hpet: None,
            количество_lapic: 0,
            количество_x2apic: 0,
            количество_ioapic: 0,
            адрес_lapic: 0,
            legacy_pic: false,
        }
    }
}

// ==========================================================================
// Парсер
// ==========================================================================

/// Подсчитать сумму байт по адресу `адрес` длиной `длина`.
///
/// # Безопасность
/// `адрес` должен быть валидным физическим адресом, доступным через
/// identity-mapping, и регион `[адрес, адрес+длина)` целиком отображён.
unsafe fn контрольная_сумма(адрес: u64, длина: usize) -> u8 {
    let мут = адрес as *const u8;
    let mut сумма: u8 = 0;
    let mut i = 0usize;
    while i < длина {
        сумма = сумма.wrapping_add(unsafe { core::ptr::read_volatile(мут.add(i)) });
        i += 1;
    }
    сумма
}

/// Прочитать заголовок SDT по физическому адресу и проверить его
/// контрольную сумму.
///
/// # Безопасность
/// Адрес должен быть валидным; первые 36 байт читаются для заголовка,
/// затем читаются ещё `длина-36` байт для проверки.
unsafe fn прочитать_сдт(адрес: u64) -> Option<ЗаголовокСДТ> {
    if адрес == 0 {
        return None;
    }
    let указатель = адрес as *const ЗаголовокСДТ;
    let заголовок = unsafe { core::ptr::read_unaligned(указатель) };
    if заголовок.длина < ЗАГОЛОВОК_РАЗМЕР as u32 {
        return None;
    }
    let сумма = unsafe { контрольная_сумма(адрес, заголовок.длина as usize) };
    if сумма != 0 {
        return None;
    }
    Some(заголовок)
}

/// Найти и инициализировать таблицы ACPI по адресу RSDP.
///
/// Возвращает `None`, если RSDP невалиден или ACPI-таблицы испорчены.
///
/// # Безопасность
/// `rsdp_адрес` должен указывать на корректный RSDP (его обычно отдаёт
/// прошивка через UEFI Configuration Table; UEFI-загрузчик ДубПрото
/// записывает его в `ИнфоЗагрузки.rsdp`).
pub unsafe fn инициализировать(rsdp_адрес: u64) -> Option<АкпиТаблицы> {
    if rsdp_адрес == 0 {
        return None;
    }

    // 1) Прочитать и проверить RSDP (минимум 20 байт ACPI 1.0).
    let rsdp10 = unsafe { core::ptr::read_unaligned(rsdp_адрес as *const Rsdp10) };
    if rsdp10.сигнатура != СИГ_RSDP {
        return None;
    }
    let базовая_сумма = unsafe { контрольная_сумма(rsdp_адрес, size_of::<Rsdp10>()) };
    if базовая_сумма != 0 {
        return None;
    }

    let mut таблицы = АкпиТаблицы::пустая();
    таблицы.ревизия_rsdp = rsdp10.ревизия;

    // 2) Если ACPI 2.0+, проверить расширенную часть и предпочесть XSDT.
    if rsdp10.ревизия >= 2 {
        let rsdp20 = unsafe { core::ptr::read_unaligned(rsdp_адрес as *const Rsdp20) };
        let длина = rsdp20.длина as usize;
        if длина >= size_of::<Rsdp20>() {
            let расш_сумма = unsafe { контрольная_сумма(rsdp_адрес, длина) };
            if расш_сумма == 0 && rsdp20.адрес_xsdt != 0 {
                таблицы.xsdt = Some(rsdp20.адрес_xsdt);
            }
        }
    }

    // 3) Иначе или как фолбэк — RSDT (32-битные указатели).
    if таблицы.xsdt.is_none() && rsdp10.адрес_rsdt != 0 {
        таблицы.rsdt = Some(rsdp10.адрес_rsdt as u64);
    }

    // 4) Прочитать root SDT и пройтись по списку указателей.
    let (корень, размер_указателя) = if let Some(адр) = таблицы.xsdt {
        (адр, 8usize)
    } else if let Some(адр) = таблицы.rsdt {
        (адр, 4usize)
    } else {
        return Some(таблицы); // RSDP есть, но указателей нет.
    };

    let заголовок = unsafe { прочитать_сдт(корень)? };
    let ожидаемая_сиг = if размер_указателя == 8 { СИГ_XSDT } else { СИГ_RSDT };
    if заголовок.сигнатура != ожидаемая_сиг {
        return Some(таблицы);
    }

    // Записи идут сразу после заголовка SDT.
    let записи_адрес = корень + ЗАГОЛОВОК_РАЗМЕР as u64;
    let записи_длина = заголовок.длина as usize - ЗАГОЛОВОК_РАЗМЕР;
    let количество = записи_длина / размер_указателя;

    for i in 0..количество {
        let адрес_указателя = записи_адрес + (i * размер_указателя) as u64;
        let адрес_таблицы: u64 = if размер_указателя == 8 {
            unsafe { core::ptr::read_unaligned(адрес_указателя as *const u64) }
        } else {
            unsafe { core::ptr::read_unaligned(адрес_указателя as *const u32) as u64 }
        };
        if адрес_таблицы == 0 {
            continue;
        }
        let Some(заг) = (unsafe { прочитать_сдт(адрес_таблицы) }) else {
            continue;
        };
        match заг.сигнатура {
            x if x == СИГ_FADT => таблицы.fadt = Some(адрес_таблицы),
            x if x == СИГ_MADT => таблицы.madt = Some(адрес_таблицы),
            x if x == СИГ_MCFG => таблицы.mcfg = Some(адрес_таблицы),
            x if x == СИГ_HPET => таблицы.hpet = Some(адрес_таблицы),
            _ => {}
        }
    }

    // 5) Если есть MADT — заполнить сводку (CPU/IOAPIC).
    if let Some(адр_madt) = таблицы.madt {
        unsafe { разобрать_madt(адр_madt, &mut таблицы) };
    }

    Some(таблицы)
}

/// Пройтись по записям MADT и заполнить сводку: количество CPU,
/// количество IO APIC, адрес Local APIC (с учётом override).
unsafe fn разобрать_madt(адрес: u64, итог: &mut АкпиТаблицы) {
    let заголовок = match unsafe { прочитать_сдт(адрес) } {
        Some(з) => з,
        None => return,
    };
    if заголовок.сигнатура != СИГ_MADT {
        return;
    }
    if заголовок.длина as usize <= ЗАГОЛОВОК_РАЗМЕР + size_of::<ЗаголовокMADT>() {
        return;
    }
    let madt_заг = unsafe {
        core::ptr::read_unaligned(
            (адрес + ЗАГОЛОВОК_РАЗМЕР as u64) as *const ЗаголовокMADT,
        )
    };
    итог.адрес_lapic = madt_заг.адрес_лапик as u64;
    итог.legacy_pic = (madt_заг.флаги & 1) != 0;

    let начало = адрес + ЗАГОЛОВОК_РАЗМЕР as u64 + size_of::<ЗаголовокMADT>() as u64;
    let конец = адрес + заголовок.длина as u64;
    let mut курсор = начало;
    while курсор + 2 <= конец {
        let тип: u8 = unsafe { core::ptr::read_volatile(курсор as *const u8) };
        let длина: u8 =
            unsafe { core::ptr::read_volatile((курсор + 1) as *const u8) };
        if длина < 2 || курсор + длина as u64 > конец {
            break;
        }
        match тип {
            MADT_LAPIC => {
                if (длина as usize) >= size_of::<MadtLapic>() {
                    let зап = unsafe {
                        core::ptr::read_unaligned(курсор as *const MadtLapic)
                    };
                    if (зап.флаги & 0b11) != 0 {
                        итог.количество_lapic = итог.количество_lapic.saturating_add(1);
                    }
                }
            }
            MADT_IOAPIC => {
                итог.количество_ioapic = итог.количество_ioapic.saturating_add(1);
            }
            MADT_LAPIC_OVR => {
                if (длина as usize) >= size_of::<MadtLapicOvr>() {
                    let зап = unsafe {
                        core::ptr::read_unaligned(курсор as *const MadtLapicOvr)
                    };
                    итог.адрес_lapic = зап.адрес;
                }
            }
            MADT_X2APIC => {
                if (длина as usize) >= size_of::<MadtX2apic>() {
                    let зап = unsafe {
                        core::ptr::read_unaligned(курсор as *const MadtX2apic)
                    };
                    if (зап.флаги & 0b11) != 0 {
                        итог.количество_x2apic = итог.количество_x2apic.saturating_add(1);
                    }
                }
            }
            _ => {}
        }
        курсор += длина as u64;
    }
}

// ==========================================================================
// Перечислители (итераторы по MADT/MCFG для будущих этапов APIC/PCIe)
// ==========================================================================

/// Перечислить все Local APIC из MADT (только enabled / online-capable).
///
/// Возвращает (acpi_proc_id, apic_id, флаги).
pub fn перечислить_lapic(
    таблицы: &АкпиТаблицы,
    mut выдать: impl FnMut(u8, u8, u32),
) {
    let Some(адрес) = таблицы.madt else { return };
    let Some(заголовок) = (unsafe { прочитать_сдт(адрес) }) else { return };
    if заголовок.сигнатура != СИГ_MADT {
        return;
    }
    let начало = адрес + ЗАГОЛОВОК_РАЗМЕР as u64 + size_of::<ЗаголовокMADT>() as u64;
    let конец = адрес + заголовок.длина as u64;
    let mut курсор = начало;
    while курсор + 2 <= конец {
        let тип: u8 = unsafe { core::ptr::read_volatile(курсор as *const u8) };
        let длина: u8 =
            unsafe { core::ptr::read_volatile((курсор + 1) as *const u8) };
        if длина < 2 || курсор + длина as u64 > конец {
            break;
        }
        if тип == MADT_LAPIC && (длина as usize) >= size_of::<MadtLapic>() {
            let зап = unsafe {
                core::ptr::read_unaligned(курсор as *const MadtLapic)
            };
            if (зап.флаги & 0b11) != 0 {
                выдать(зап.acpi_proc_id, зап.apic_id, зап.флаги);
            }
        }
        курсор += длина as u64;
    }
}

/// Перечислить IO APIC из MADT. Передаёт (id, физ. адрес, gsi_base).
pub fn перечислить_ioapic(
    таблицы: &АкпиТаблицы,
    mut выдать: impl FnMut(u8, u32, u32),
) {
    let Some(адрес) = таблицы.madt else { return };
    let Some(заголовок) = (unsafe { прочитать_сдт(адрес) }) else { return };
    if заголовок.сигнатура != СИГ_MADT {
        return;
    }
    let начало = адрес + ЗАГОЛОВОК_РАЗМЕР as u64 + size_of::<ЗаголовокMADT>() as u64;
    let конец = адрес + заголовок.длина as u64;
    let mut курсор = начало;
    while курсор + 2 <= конец {
        let тип: u8 = unsafe { core::ptr::read_volatile(курсор as *const u8) };
        let длина: u8 =
            unsafe { core::ptr::read_volatile((курсор + 1) as *const u8) };
        if длина < 2 || курсор + длина as u64 > конец {
            break;
        }
        if тип == MADT_IOAPIC && (длина as usize) >= size_of::<MadtIoapic>() {
            let зап = unsafe {
                core::ptr::read_unaligned(курсор as *const MadtIoapic)
            };
            выдать(зап.id, зап.адрес, зап.база_gsi);
        }
        курсор += длина as u64;
    }
}

/// Перечислить Interrupt Source Override из MADT.
/// Передаёт (legacy_irq, gsi, флаги).
pub fn перечислить_iso(
    таблицы: &АкпиТаблицы,
    mut выдать: impl FnMut(u8, u32, u16),
) {
    let Some(адрес) = таблицы.madt else { return };
    let Some(заголовок) = (unsafe { прочитать_сдт(адрес) }) else { return };
    if заголовок.сигнатура != СИГ_MADT {
        return;
    }
    let начало = адрес + ЗАГОЛОВОК_РАЗМЕР as u64 + size_of::<ЗаголовокMADT>() as u64;
    let конец = адрес + заголовок.длина as u64;
    let mut курсор = начало;
    while курсор + 2 <= конец {
        let тип: u8 = unsafe { core::ptr::read_volatile(курсор as *const u8) };
        let длина: u8 =
            unsafe { core::ptr::read_volatile((курсор + 1) as *const u8) };
        if длина < 2 || курсор + длина as u64 > конец {
            break;
        }
        if тип == MADT_ISO && (длина as usize) >= size_of::<MadtIso>() {
            let зап = unsafe {
                core::ptr::read_unaligned(курсор as *const MadtIso)
            };
            выдать(зап.источник, зап.gsi, зап.флаги);
        }
        курсор += длина as u64;
    }
}

/// Найти GSI, на который перенаправлен legacy IRQ (через ISO записи MADT).
/// Если переопределения нет — возвращает None (используйте 1:1 маршрут).
pub fn переопределение_gsi(legacy_irq: u8) -> Option<u32> {
    let таблицы = описание()?;
    let mut результат: Option<u32> = None;
    перечислить_iso(&таблицы, |источник, gsi, _ф| {
        if источник == legacy_irq {
            результат = Some(gsi);
        }
    });
    результат
}

/// Перечислить ECAM-сегменты PCIe из MCFG.
/// Передаёт (база_ecam, сегмент, шина_начало, шина_конец).
pub fn перечислить_pcie_сегменты(
    таблицы: &АкпиТаблицы,
    mut выдать: impl FnMut(u64, u16, u8, u8),
) {
    let Some(адрес) = таблицы.mcfg else { return };
    let Some(заголовок) = (unsafe { прочитать_сдт(адрес) }) else { return };
    if заголовок.сигнатура != СИГ_MCFG {
        return;
    }
    // После общего SDT-заголовка идут 8 байт reserved, затем массив записей.
    let начало = адрес + ЗАГОЛОВОК_РАЗМЕР as u64 + 8;
    let конец = адрес + заголовок.длина as u64;
    let mut курсор = начало;
    while курсор + size_of::<McfgЗапись>() as u64 <= конец {
        let зап = unsafe { core::ptr::read_unaligned(курсор as *const McfgЗапись) };
        выдать(зап.база_ecam, зап.сегмент, зап.шина_начало, зап.шина_конец);
        курсор += size_of::<McfgЗапись>() as u64;
    }
}

/// Прочитать запись HPET (если есть). Возвращает (адрес_hpet, минимальный_тик).
pub fn прочитать_hpet(таблицы: &АкпиТаблицы) -> Option<(u64, u16)> {
    let адрес = таблицы.hpet?;
    let заголовок = unsafe { прочитать_сдт(адрес)? };
    if заголовок.сигнатура != СИГ_HPET {
        return None;
    }
    let h = unsafe {
        core::ptr::read_unaligned(
            (адрес + ЗАГОЛОВОК_РАЗМЕР as u64) as *const ЗаголовокHPET,
        )
    };
    Some((h.база.адрес, h.минимальный_тик))
}

// ==========================================================================
// Глобальный singleton + удобный доступ
// ==========================================================================

use spin::Mutex;

static ОПИСАНИЕ: Mutex<Option<АкпиТаблицы>> = Mutex::new(None);

/// Сохранить описание ACPI глобально (вызывается один раз из `главный.rs`).
pub fn установить(таблицы: АкпиТаблицы) {
    *ОПИСАНИЕ.lock() = Some(таблицы);
}

/// Получить копию текущего описания ACPI, если оно проинициализировано.
pub fn описание() -> Option<АкпиТаблицы> {
    *ОПИСАНИЕ.lock()
}

// ==========================================================================
// Самопроверка / диагностика
// ==========================================================================

/// Распечатать сводку ACPI в журнал (доступные таблицы, сводку MADT/MCFG/HPET).
pub fn распечатать_сводку(таблицы: &АкпиТаблицы) {
    let имя_версии = if таблицы.ревизия_rsdp >= 2 { "ACPI 2.0+ (XSDT)" } else { "ACPI 1.0 (RSDT)" };
    println!("[OK]   ACPI: {}", имя_версии);
    if let Some(а) = таблицы.xsdt {
        println!("[ИНФО] XSDT     @ 0x{:X}", а);
    }
    if let Some(а) = таблицы.rsdt {
        println!("[ИНФО] RSDT     @ 0x{:X}", а);
    }
    if let Some(а) = таблицы.fadt {
        println!("[ИНФО] FADT     @ 0x{:X}", а);
    }
    if let Some(а) = таблицы.madt {
        println!("[ИНФО] MADT     @ 0x{:X}", а);
        println!(
            "       CPU LAPIC: {}, x2APIC: {}, IO APIC: {}, legacy 8259 PIC: {}",
            таблицы.количество_lapic,
            таблицы.количество_x2apic,
            таблицы.количество_ioapic,
            if таблицы.legacy_pic { "да" } else { "нет" },
        );
        println!("       Local APIC base: 0x{:X}", таблицы.адрес_lapic);
        перечислить_ioapic(таблицы, |id, адрес, gsi| {
            println!(
                "       IO APIC #{}: addr 0x{:X}, GSI base {}",
                id, адрес, gsi
            );
        });
        let mut iso_count = 0u32;
        перечислить_iso(таблицы, |_irq, _gsi, _ф| {
            iso_count += 1;
        });
        if iso_count > 0 {
            println!("       Interrupt Source Override записей: {}", iso_count);
        }
    } else {
        println!("[ВНИМАНИЕ] MADT отсутствует — APIC-инициализация невозможна");
    }
    if let Some(а) = таблицы.mcfg {
        println!("[ИНФО] MCFG     @ 0x{:X} (PCIe ECAM доступен)", а);
        перечислить_pcie_сегменты(таблицы, |база, сег, шб, шк| {
            println!(
                "       PCIe сегмент {}: ECAM 0x{:X}, шины {}..{}",
                сег, база, шб, шк
            );
        });
    } else {
        println!("[ИНФО] MCFG отсутствует — PCIe enumeration через legacy 0xCF8/0xCFC");
    }
    if let Some((адрес, мин)) = прочитать_hpet(таблицы) {
        println!(
            "[ИНФО] HPET     @ 0x{:X}, minimum tick = {} fs",
            адрес, мин
        );
    } else {
        println!("[ИНФО] HPET отсутствует — будем использовать PIT/APIC-таймер");
    }
}
