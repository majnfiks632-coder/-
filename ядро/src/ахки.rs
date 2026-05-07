// ДубинаОС
// Авторские права (C) 2026, Команда ДубинаОС
// Лицензия: GPL-3.0-or-later
//
// AHCI (Advanced Host Controller Interface) — драйвер контроллера SATA.
//
// На современных машинах (включая ASUS X552E) SATA-контроллер выглядит как
// PCI-устройство класса 0x01:0x06:0x01. BAR5 (ABAR) указывает на MMIO-окно
// HBA (Host Bus Adapter). HBA имеет:
//
//   * глобальные регистры (offset 0..0x100): GHC, CAP, PI, ...
//   * до 32 порт-блоков (offset 0x100 + N*0x80) — по одному на канал.
//
// На этом этапе мы:
//   1) Находим AHCI-устройство по PCI.
//   2) Включаем bus mastering и memory space.
//   3) Разрешаем AHCI mode (GHC.AE = 1).
//   4) Перебираем порты по битмаске PI.
//   5) Для каждого порта читаем SSTS (Serial ATA Status) — определяем,
//      подключено ли устройство и его скорость.
//   6) Для подключённых читаем SIG (signature) — SATA / SATAPI / Enclosure.
//
// Чтение/запись секторов (READ/WRITE DMA EXT) — следующий этап (3.2).

#![allow(dead_code)]

use crate::println;
use crate::писиай;

// ==========================================================================
// Регистры HBA
// ==========================================================================

/// Глобальные регистры (offset от ABAR).
const HBA_CAP: u64 = 0x00;          // Host Capabilities
const HBA_GHC: u64 = 0x04;          // Global Host Control
const HBA_IS: u64 = 0x08;           // Interrupt Status
const HBA_PI: u64 = 0x0C;           // Ports Implemented
const HBA_VS: u64 = 0x10;           // Version
const HBA_CCC_CTL: u64 = 0x14;
const HBA_CCC_PORTS: u64 = 0x18;
const HBA_EM_LOC: u64 = 0x1C;
const HBA_EM_CTL: u64 = 0x20;
const HBA_CAP2: u64 = 0x24;
const HBA_BOHC: u64 = 0x28;          // BIOS/OS Handoff Control

const GHC_AE: u32 = 1 << 31;        // AHCI Enable
const GHC_HR: u32 = 1 << 0;         // HBA Reset

/// Регистры порта (offset от 0x100 + N*0x80).
const PORT_CLB: u64 = 0x00;         // Command List Base (low)
const PORT_CLBU: u64 = 0x04;        // Command List Base (high)
const PORT_FB: u64 = 0x08;          // FIS Base (low)
const PORT_FBU: u64 = 0x0C;         // FIS Base (high)
const PORT_IS: u64 = 0x10;
const PORT_IE: u64 = 0x14;
const PORT_CMD: u64 = 0x18;
const PORT_TFD: u64 = 0x20;
const PORT_SIG: u64 = 0x24;         // Signature
const PORT_SSTS: u64 = 0x28;        // SATA Status
const PORT_SCTL: u64 = 0x2C;
const PORT_SERR: u64 = 0x30;
const PORT_SACT: u64 = 0x34;
const PORT_CI: u64 = 0x38;          // Command Issue

const PORT_CMD_ST: u32 = 1 << 0;
const PORT_CMD_FRE: u32 = 1 << 4;
const PORT_CMD_FR: u32 = 1 << 14;
const PORT_CMD_CR: u32 = 1 << 15;

// Команды ATA.
const ATA_CMD_READ_DMA_EXT: u8 = 0x25;
const ATA_CMD_WRITE_DMA_EXT: u8 = 0x35;
const ATA_CMD_IDENTIFY: u8 = 0xEC;

// Биты Task File Status.
const TFD_BSY: u32 = 1 << 7;
const TFD_DRQ: u32 = 1 << 3;
const TFD_ERR: u32 = 1 << 0;

// Сигнатуры устройств в PORT_SIG.
const SIG_SATA: u32 = 0x0000_0101;
const SIG_SATAPI: u32 = 0xEB14_0101;
const SIG_SEMB: u32 = 0xC33C_0101;
const SIG_PM: u32 = 0x9669_0101;

// ==========================================================================
// Описание устройства
// ==========================================================================

#[derive(Debug, Clone, Copy)]
pub struct ИнфоAHCI {
    pub база_hba: u64,
    pub адрес_pci: писиай::АдресPCI,
    pub версия: u32,
    pub возможности: u32,
    pub количество_портов: u8,
    pub реализованные_порты: u32, // битмаска
    pub количество_активных: u8,
}

#[derive(Debug, Clone, Copy)]
pub struct ИнфоПорта {
    pub номер: u8,
    pub сигнатура: u32,
    pub ссылка: u8,         // PORT_SSTS.det
    pub скорость: u8,       // PORT_SSTS.spd
    pub power: u8,          // PORT_SSTS.ipm
}

impl ИнфоПорта {
    pub fn тип_устройства(&self) -> &'static str {
        match self.сигнатура {
            SIG_SATA => "SATA",
            SIG_SATAPI => "SATAPI (CD/DVD)",
            SIG_SEMB => "SEMB (enclosure)",
            SIG_PM => "Port Multiplier",
            _ => "неизвестное",
        }
    }

    pub fn скорость_мбит(&self) -> u32 {
        match self.скорость {
            1 => 1500,  // 1.5 Гб/с
            2 => 3000,  // 3 Гб/с
            3 => 6000,  // 6 Гб/с
            _ => 0,
        }
    }

    pub fn активен(&self) -> bool {
        // ssts.det == 3 → device present, communication established.
        self.ссылка == 3 && self.power != 0
    }
}

// ==========================================================================
// Низкоуровневое чтение/запись регистров HBA
// ==========================================================================

unsafe fn читать_hba(база: u64, смещение: u64) -> u32 {
    unsafe { core::ptr::read_volatile((база + смещение) as *const u32) }
}

unsafe fn записать_hba(база: u64, смещение: u64, значение: u32) {
    unsafe { core::ptr::write_volatile((база + смещение) as *mut u32, значение) }
}

unsafe fn читать_порт(база: u64, порт: u8, смещение: u64) -> u32 {
    unsafe { читать_hba(база, 0x100 + (порт as u64) * 0x80 + смещение) }
}

unsafe fn записать_порт(база: u64, порт: u8, смещение: u64, значение: u32) {
    unsafe { записать_hba(база, 0x100 + (порт as u64) * 0x80 + смещение, значение) }
}

// ==========================================================================
// Поиск и инициализация HBA
// ==========================================================================

/// Найти AHCI-контроллер на шине PCI.
pub fn найти() -> Option<писиай::ИнфоPCI> {
    писиай::найти_с_progif(0x01, 0x06, 0x01)
}

/// Поднять AHCI: получить BAR5, включить AHCI mode, перечислить порты.
///
/// Возвращает `Some(ИнфоAHCI)` при успехе. На реальном железе (X552E)
/// это даст наш первый момент видеть жёсткий диск.
pub fn инициализировать() -> Option<ИнфоAHCI> {
    let pci = найти()?;
    println!(
        "[ИНФО] AHCI: найден контроллер {:04X}:{:04X} в {:02X}:{:02X}.{}",
        pci.vendor, pci.device, pci.адрес.шина, pci.адрес.устройство, pci.адрес.функция
    );
    // BAR5 = ABAR.
    let база = писиай::прочитать_bar(pci.адрес, 5);
    if база == 0 {
        println!("[ВНИМАНИЕ] AHCI: BAR5 == 0, не можем инициализировать");
        return None;
    }
    println!("[ИНФО] AHCI: ABAR = 0x{:X}", база);
    if база >= 0x1_0000_0000 {
        println!("[ВНИМАНИЕ] AHCI: ABAR выше 4 ГиБ — у нас identity-mapping не покрывает");
        return None;
    }

    // Включаем bus master и memory space — без этого DMA не пойдёт.
    писиай::включить_bus_master(pci.адрес);

    // Разрешаем AHCI mode (на старых контроллерах он может быть в legacy SATA).
    let ghc = unsafe { читать_hba(база, HBA_GHC) };
    unsafe { записать_hba(база, HBA_GHC, ghc | GHC_AE) };

    let версия = unsafe { читать_hba(база, HBA_VS) };
    let cap = unsafe { читать_hba(база, HBA_CAP) };
    let pi = unsafe { читать_hba(база, HBA_PI) };
    let max_портов = ((cap & 0x1F) + 1) as u8;

    println!(
        "[ИНФО] AHCI: версия 0x{:08X}, CAP 0x{:08X}, портов {}, реализовано 0x{:08X}",
        версия, cap, max_портов, pi
    );

    let mut активных = 0u8;
    for номер in 0..32u8 {
        if pi & (1u32 << номер) == 0 {
            continue;
        }
        let Some(инфо) = опросить_порт(база, номер) else { continue };
        if инфо.активен() {
            активных += 1;
            println!(
                "[OK]   AHCI порт {}: {} ({} Мбит/с), сигнатура 0x{:08X}",
                инфо.номер, инфо.тип_устройства(), инфо.скорость_мбит(), инфо.сигнатура
            );
        } else {
            println!(
                "[ИНФО] AHCI порт {}: устройство не подключено (DET={}, IPM={})",
                инфо.номер, инфо.ссылка, инфо.power
            );
        }
    }

    Some(ИнфоAHCI {
        база_hba: база,
        адрес_pci: pci.адрес,
        версия,
        возможности: cap,
        количество_портов: max_портов,
        реализованные_порты: pi,
        количество_активных: активных,
    })
}

// ==========================================================================
// Структуры в DMA-памяти HBA
// ==========================================================================
//
// Каждый порт требует:
//   * Command List — 32 заголовка по 32 байта (1 КиБ, выравнивание 1 КиБ).
//   * Received FIS — 256 байт (выравнивание 256).
//   * На каждый слот — Command Table (Command FIS + PRDT-записи).
//
// Суммарно для одного порта на одну команду: 1024 + 256 + (128 + 16*N) ~ 4 КиБ.
// Мы используем по одной 4-КиБ странице на CL+FIS и по одной на Command Table.

/// Заголовок команды (Command Header) в Command List.
/// Размер 32 байта.
#[repr(C, packed)]
#[derive(Clone, Copy)]
struct ЗаголовокКоманды {
    /// CFL[4:0] | A | W | P | R | B | C | rsv | PMP[3:0]
    флаги: u16,
    /// Число PRDT-записей.
    prdtl: u16,
    /// Сколько байт перенесено в PRDT (записывается контроллером).
    prdbc: u32,
    /// Адрес Command Table (физический, выровнен на 128).
    ctba: u32,
    ctbau: u32,
    rsv: [u32; 4],
}

/// Запись PRDT (Physical Region Descriptor Table). Размер 16 байт.
#[repr(C, packed)]
#[derive(Clone, Copy)]
struct ЗаписьPRDT {
    dba: u32,        // адрес буфера
    dbau: u32,
    rsv: u32,
    /// Биты 0..21 = размер-1; бит 31 = I (interrupt on completion).
    dbc: u32,
}

/// Command Table: 64 байта Command FIS + 16 байт ATAPI + 48 байт зарезервировано
/// + N×16 байт PRDT. Минимально под 1 PRDT = 128 + 16 = 144 байт; округляем до 256.
#[repr(C, packed)]
#[derive(Clone, Copy)]
struct CommandTable {
    cfis: [u8; 64],
    atapi: [u8; 16],
    rsv: [u8; 48],
    prdt: [ЗаписьPRDT; 8],
}

/// FIS типа H2D Register (тип 0x27). Размер 20 байт.
#[repr(C, packed)]
#[derive(Clone, Copy, Default)]
struct H2DFis {
    тип: u8,        // 0x27
    флаги: u8,      // bit 7 = C (1 для command)
    команда: u8,
    feat_low: u8,
    lba0: u8, lba1: u8, lba2: u8, device: u8,
    lba3: u8, lba4: u8, lba5: u8, feat_high: u8,
    count_low: u8, count_high: u8,
    icc: u8, control: u8,
    aux: [u8; 4],
}

const ТИП_H2D_REG: u8 = 0x27;

// ==========================================================================
// IDENTIFY DEVICE — узнаём модель и размер диска
// ==========================================================================

/// Информация, извлечённая из IDENTIFY DEVICE.
#[derive(Debug, Clone, Copy)]
pub struct ИнфоДиска {
    pub номер_порта: u8,
    pub модель: [u8; 40],
    pub серийник: [u8; 20],
    pub лба48_доступен: bool,
    pub секторов_лба28: u32,
    pub секторов_лба48: u64,
    pub размер_сектора: u32,
}

impl ИнфоДиска {
    pub fn размер_байт(&self) -> u64 {
        let секторов = if self.лба48_доступен && self.секторов_лба48 > 0 {
            self.секторов_лба48
        } else {
            self.секторов_лба28 as u64
        };
        секторов * self.размер_сектора as u64
    }
}

/// Выделить одну физическую страницу 4 КиБ. Возвращает (физ_адрес, ptr).
/// Так как у нас identity mapping в нижних адресах, физ == вирт.
fn выделить_страницу_dma() -> Option<(u64, *mut u8)> {
    let физ = match crate::память::физическая::АЛЛОКАТОР.lock().выделить_страницу() {
        Ok(а) => а.значение(),
        Err(_) => return None,
    };
    if физ >= 0x1_0000_0000 {
        // DMA выше 4 ГиБ не поддерживаем без 64-bit addressing.
        return None;
    }
    // Обнулить страницу.
    unsafe {
        core::ptr::write_bytes(физ as *mut u8, 0, 4096);
    }
    Some((физ, физ as *mut u8))
}

/// Запустить команду на порту: ждать готовности → отправить → ждать
/// завершения. Используем слот 0 (один in-flight за раз).
unsafe fn выполнить_команду(
    база: u64,
    порт: u8,
    cl_phys: u64,
    fb_phys: u64,
    ct_phys: u64,
    fis: &H2DFis,
    буфер_phys: u64,
    байт_буфер: u32,
    запись: bool,
) -> Result<(), &'static str> {
    unsafe {
        // Привязать список команд и FIS-приёмник.
        записать_порт(база, порт, PORT_CLB, (cl_phys & 0xFFFF_FFFF) as u32);
        записать_порт(база, порт, PORT_CLBU, (cl_phys >> 32) as u32);
        записать_порт(база, порт, PORT_FB, (fb_phys & 0xFFFF_FFFF) as u32);
        записать_порт(база, порт, PORT_FBU, (fb_phys >> 32) as u32);

        // Запустить FRE — приём FIS-ов от диска.
        let cmd = читать_порт(база, порт, PORT_CMD);
        записать_порт(база, порт, PORT_CMD, cmd | PORT_CMD_FRE);

        // Сбросить SERR / IS.
        записать_порт(база, порт, PORT_SERR, 0xFFFF_FFFF);
        записать_порт(база, порт, PORT_IS, 0xFFFF_FFFF);

        // Ждать BSY=DRQ=0.
        let mut ждём = 0u32;
        while читать_порт(база, порт, PORT_TFD) & (TFD_BSY | TFD_DRQ) != 0 {
            ждём += 1;
            if ждём > 1_000_000 {
                return Err("порт не готов перед командой");
            }
        }

        // Заполнить заголовок команды (slot 0).
        let cl = cl_phys as *mut ЗаголовокКоманды;
        let mut флаги: u16 = (size_of::<H2DFis>() / 4) as u16;
        if запись { флаги |= 1 << 6; } // W
        // C-bit — это часть FIS, не header; в header ставим CFL=fis_dwords.
        (*cl).флаги = флаги;
        (*cl).prdtl = 1;
        (*cl).prdbc = 0;
        (*cl).ctba = (ct_phys & 0xFFFF_FFFF) as u32;
        (*cl).ctbau = (ct_phys >> 32) as u32;
        for i in 0..4 { (*cl).rsv[i] = 0; }

        // Заполнить Command Table.
        let ct = ct_phys as *mut CommandTable;
        // CFIS = наш H2D.
        let fis_ptr = &(*ct).cfis as *const u8 as *mut u8;
        core::ptr::copy_nonoverlapping(
            fis as *const _ as *const u8,
            fis_ptr,
            size_of::<H2DFis>(),
        );
        // PRDT[0] = буфер.
        (*ct).prdt[0].dba = (буфер_phys & 0xFFFF_FFFF) as u32;
        (*ct).prdt[0].dbau = (буфер_phys >> 32) as u32;
        (*ct).prdt[0].rsv = 0;
        // dbc: байт-1; флаг I=0 (без прерывания, опрашиваем CI).
        (*ct).prdt[0].dbc = (байт_буфер - 1) & 0x003F_FFFF;

        // Запустить порт (ST).
        let cmd = читать_порт(база, порт, PORT_CMD);
        записать_порт(база, порт, PORT_CMD, cmd | PORT_CMD_ST);

        // Запустить слот 0.
        записать_порт(база, порт, PORT_CI, 1);

        // Ждать завершения: CI bit 0 → 0, без TFD.ERR.
        ждём = 0;
        loop {
            let ci = читать_порт(база, порт, PORT_CI);
            if ci & 1 == 0 { break; }
            let tfd = читать_порт(база, порт, PORT_TFD);
            if tfd & TFD_ERR != 0 {
                return Err("ATA ERROR во время выполнения");
            }
            ждём += 1;
            if ждём > 50_000_000 {
                return Err("команда не завершилась за тайм-аут");
            }
        }
        let tfd = читать_порт(база, порт, PORT_TFD);
        if tfd & TFD_ERR != 0 {
            return Err("ATA ERROR после команды");
        }
        Ok(())
    }
}

/// Запустить IDENTIFY DEVICE и распечатать информацию о диске.
/// Возвращает `ИнфоДиска` при успехе.
pub fn идентифицировать(инфо: &ИнфоAHCI, номер_порта: u8) -> Option<ИнфоДиска> {
    // Память: 1 страница под CL+FIS, 1 под Command Table, 1 под IDENTIFY-буфер.
    let (cl_phys, _) = выделить_страницу_dma()?;
    let (ct_phys, _) = выделить_страницу_dma()?;
    let (буфер_phys, _) = выделить_страницу_dma()?;
    // FB сразу после CL в той же странице (1 КиБ + 256 байт точно укладывается).
    let fb_phys = cl_phys + 1024;

    let mut fis = H2DFis::default();
    fis.тип = ТИП_H2D_REG;
    fis.флаги = 1 << 7; // C-bit
    fis.команда = ATA_CMD_IDENTIFY;
    fis.device = 0;

    let результат = unsafe {
        выполнить_команду(
            инфо.база_hba,
            номер_порта,
            cl_phys,
            fb_phys,
            ct_phys,
            &fis,
            буфер_phys,
            512,
            false,
        )
    };

    let _ = крест_страницу(cl_phys);
    let _ = крест_страницу(ct_phys);

    if let Err(сообщ) = результат {
        крест_страницу(буфер_phys);
        println!("[ВНИМАНИЕ] AHCI порт {}: IDENTIFY не удалось — {}", номер_порта, сообщ);
        return None;
    }

    // Распарсить буфер IDENTIFY (512 байт; слова 16-битные little-endian,
    // строки в big-endian-парах).
    let данные = unsafe { core::slice::from_raw_parts(буфер_phys as *const u16, 256) };
    let mut модель = [0u8; 40];
    извлечь_строку(данные, 27, 47, &mut модель);
    let mut серийник = [0u8; 20];
    извлечь_строку(данные, 10, 20, &mut серийник);

    let секторов_28 = ((данные[60] as u32) | ((данные[61] as u32) << 16)) as u32;
    let лба48 = (данные[83] & (1 << 10)) != 0;
    let секторов_48 = (данные[100] as u64)
        | ((данные[101] as u64) << 16)
        | ((данные[102] as u64) << 32)
        | ((данные[103] as u64) << 48);
    let размер_сектора: u32 = 512;

    let диск = ИнфоДиска {
        номер_порта,
        модель,
        серийник,
        лба48_доступен: лба48,
        секторов_лба28: секторов_28,
        секторов_лба48: секторов_48,
        размер_сектора,
    };

    println!(
        "[OK]   AHCI порт {}: {} {}, {} МиБ ({} секторов, LBA48={})",
        номер_порта,
        строка_подрезанная(&диск.модель),
        if !диск.серийник.iter().all(|&b| b == 0) {
            "S/N:"
        } else { "" },
        диск.размер_байт() / (1024 * 1024),
        if лба48 { диск.секторов_лба48 } else { диск.секторов_лба28 as u64 },
        лба48
    );

    крест_страницу(буфер_phys);
    Some(диск)
}

/// Прочитать секторы LBA48 из диска в выделенный 4-КиБ буфер.
/// Возвращает физический адрес буфера; вызывающий обязан освободить.
pub fn прочитать_секторы(
    инфо: &ИнфоAHCI,
    номер_порта: u8,
    лба: u64,
    секторов: u16,
) -> Option<u64> {
    if секторов == 0 || секторов as u64 * 512 > 4096 {
        return None;
    }
    let (cl_phys, _) = выделить_страницу_dma()?;
    let (ct_phys, _) = выделить_страницу_dma()?;
    let (буфер_phys, _) = выделить_страницу_dma()?;
    let fb_phys = cl_phys + 1024;

    let mut fis = H2DFis::default();
    fis.тип = ТИП_H2D_REG;
    fis.флаги = 1 << 7;
    fis.команда = ATA_CMD_READ_DMA_EXT;
    fis.device = 1 << 6; // LBA mode
    fis.lba0 = (лба & 0xFF) as u8;
    fis.lba1 = ((лба >> 8) & 0xFF) as u8;
    fis.lba2 = ((лба >> 16) & 0xFF) as u8;
    fis.lba3 = ((лба >> 24) & 0xFF) as u8;
    fis.lba4 = ((лба >> 32) & 0xFF) as u8;
    fis.lba5 = ((лба >> 40) & 0xFF) as u8;
    fis.count_low = (секторов & 0xFF) as u8;
    fis.count_high = ((секторов >> 8) & 0xFF) as u8;

    let размер = (секторов as u32) * 512;
    let рез = unsafe {
        выполнить_команду(
            инфо.база_hba,
            номер_порта,
            cl_phys,
            fb_phys,
            ct_phys,
            &fis,
            буфер_phys,
            размер,
            false,
        )
    };

    крест_страницу(cl_phys);
    крест_страницу(ct_phys);

    if рез.is_err() {
        крест_страницу(буфер_phys);
        return None;
    }
    Some(буфер_phys)
}

/// Освободить страницу, выделенную выделить_страницу_dma.
fn крест_страницу(физ: u64) {
    let _ = crate::память::физическая::АЛЛОКАТОР
        .lock()
        .освободить_страницу(crate::память::ФизАдрес::новый(физ));
}

/// IDENTIFY-строки лежат в big-endian парах (старший байт в чётной позиции).
fn извлечь_строку(данные: &[u16], нач: usize, конец: usize, выход: &mut [u8]) {
    let mut вых_i = 0;
    for i in нач..конец {
        if вых_i + 1 >= выход.len() { break; }
        let w = данные[i];
        выход[вых_i] = (w >> 8) as u8;
        выход[вых_i + 1] = (w & 0xFF) as u8;
        вых_i += 2;
    }
    // Подрежем хвостовые пробелы.
    for b in выход.iter_mut().rev() {
        if *b == b' ' || *b == 0 { *b = 0; } else { break; }
    }
}

fn строка_подрезанная(буфер: &[u8]) -> &str {
    let конец = буфер.iter().position(|&b| b == 0).unwrap_or(буфер.len());
    core::str::from_utf8(&буфер[..конец]).unwrap_or("?")
}

/// Проверить, что порт активен (DET == 3, IPM != 0) и подключён SATA.
pub fn порт_активен(инфо: &ИнфоAHCI, номер: u8) -> bool {
    let Some(п) = опросить_порт(инфо.база_hba, номер) else { return false };
    п.активен() && п.сигнатура == SIG_SATA
}

/// Опросить состояние одного порта.
fn опросить_порт(база: u64, номер: u8) -> Option<ИнфоПорта> {
    let ssts = unsafe { читать_порт(база, номер, PORT_SSTS) };
    let det = (ssts & 0xF) as u8;
    let spd = ((ssts >> 4) & 0xF) as u8;
    let ipm = ((ssts >> 8) & 0xF) as u8;
    let sig = unsafe { читать_порт(база, номер, PORT_SIG) };
    Some(ИнфоПорта {
        номер,
        сигнатура: sig,
        ссылка: det,
        скорость: spd,
        power: ipm,
    })
}
