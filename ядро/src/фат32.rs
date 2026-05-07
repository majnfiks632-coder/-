// ДубинаОС
// Авторские права (C) 2026, Команда ДубинаОС
// Лицензия: GPL-3.0-or-later
//
// Минимальный читатель FAT32. Цель — открыть ESP (EFI System Partition)
// на ASUS X552E или другом UEFI-ПК и иметь возможность прочитать оттуда
// файлы (BOOTX64.EFI, конфиги загрузчика, сам ELF ядра).
//
// На этом этапе:
//   * Разбираем BIOS Parameter Block (BPB): размер сектора, кластера,
//     зарезервированных секторов, число FAT, размер FAT, корневой кластер.
//   * Умеем по номеру кластера найти его на диске.
//   * Умеем перечислить файлы в корневом каталоге.
//
// Запись и LFN-имена пока не поддерживаем — этап 3.5/3.6.

#![allow(dead_code)]

use crate::println;
use crate::ахки;

#[repr(C, packed)]
#[derive(Clone, Copy)]
struct BPB32 {
    jmp: [u8; 3],
    oem: [u8; 8],
    bytes_per_sector: u16,
    sectors_per_cluster: u8,
    reserved_sectors: u16,
    num_fats: u8,
    root_entries: u16,        // на FAT32 == 0
    total_sectors_16: u16,    // 0 на FAT32, см. total_sectors_32
    media: u8,
    fat_size_16: u16,         // 0 на FAT32
    sectors_per_track: u16,
    num_heads: u16,
    hidden_sectors: u32,
    total_sectors_32: u32,
    // FAT32-extended:
    fat_size_32: u32,
    ext_flags: u16,
    fs_version: u16,
    root_cluster: u32,
    fs_info: u16,
    backup_boot: u16,
    reserved: [u8; 12],
    drive_num: u8,
    reserved1: u8,
    boot_sig: u8,
    volume_id: u32,
    volume_label: [u8; 11],
    fs_type: [u8; 8],
}

#[derive(Debug, Clone, Copy)]
pub struct ТомFAT32 {
    /// LBA начала тома (от начала диска).
    pub лба_тома: u64,
    pub байт_в_секторе: u32,
    pub секторов_в_кластере: u32,
    pub зарез_секторов: u32,
    pub количество_fat: u32,
    pub размер_fat: u32,
    pub корневой_кластер: u32,
    pub лба_fat: u64,
    pub лба_данных: u64,
    pub метка: [u8; 11],
}

impl ТомFAT32 {
    /// LBA первого сектора кластера N.
    pub fn кластер_lba(&self, кластер: u32) -> u64 {
        self.лба_данных + ((кластер - 2) as u64) * self.секторов_в_кластере as u64
    }

    pub fn байт_в_кластере(&self) -> u32 {
        self.байт_в_секторе * self.секторов_в_кластере
    }

    pub fn метка_строкой(&self) -> &str {
        let конец = self.метка.iter().rposition(|&b| b != b' ' && b != 0).map(|i| i + 1).unwrap_or(0);
        core::str::from_utf8(&self.метка[..конец]).unwrap_or("?")
    }
}

/// Прочитать BPB по LBA первого сектора тома и собрать `ТомFAT32`.
pub fn открыть_том(хба: &ахки::ИнфоAHCI, порт: u8, лба_тома: u64) -> Option<ТомFAT32> {
    let phys = ахки::прочитать_секторы(хба, порт, лба_тома, 1)?;
    let bpb: BPB32 = unsafe { core::ptr::read_unaligned(phys as *const BPB32) };
    let освободить = || {
        let _ = crate::память::физическая::АЛЛОКАТОР
            .lock()
            .освободить_страницу(crate::память::ФизАдрес::новый(phys));
    };

    // Простые проверки. Поля packed нельзя референсить напрямую — копируем.
    let bps = bpb.bytes_per_sector;
    let spc = bpb.sectors_per_cluster;
    if bps != 512 || spc == 0 {
        освободить();
        println!("[ВНИМАНИЕ] FAT32: некорректный BPB (sec={}, spc={})", bps, spc);
        return None;
    }
    if bpb.fat_size_32 == 0 {
        освободить();
        println!("[ВНИМАНИЕ] FAT32: fat_size_32 == 0 — это не FAT32");
        return None;
    }
    let fs_type = bpb.fs_type;
    if &fs_type[0..5] != b"FAT32" {
        // Часто прошивки ставят FAT16/FAT12 или мусор; проверяем по факту по
        // размеру FAT и root_cluster, но предупредим.
        println!(
            "[ИНФО] FAT32: fs_type=«{}» — продолжаем по BPB-параметрам",
            core::str::from_utf8(&fs_type).unwrap_or("?")
        );
    }

    let зарез = bpb.reserved_sectors as u32;
    let num_fats = bpb.num_fats as u32;
    let fat_size = bpb.fat_size_32;
    let лба_fat = лба_тома + зарез as u64;
    let лба_данных = лба_fat + (num_fats * fat_size) as u64;

    let метка = bpb.volume_label;
    let байт_в_секторе = bpb.bytes_per_sector as u32;
    let спк = bpb.sectors_per_cluster as u32;
    let корневой = bpb.root_cluster;
    освободить();

    println!(
        "[OK]   FAT32 открыт: метка «{}», секторов в кластере {}, FAT @ LBA {}, данные @ LBA {}",
        строка_метки(&метка), спк, лба_fat, лба_данных
    );
    println!(
        "       зарез. секторов {}, размер FAT {} секторов, FAT-копий {}, корень-кластер {}",
        зарез, fat_size, num_fats, корневой
    );

    Some(ТомFAT32 {
        лба_тома,
        байт_в_секторе,
        секторов_в_кластере: спк,
        зарез_секторов: зарез,
        количество_fat: num_fats,
        размер_fat: fat_size,
        корневой_кластер: корневой,
        лба_fat,
        лба_данных,
        метка,
    })
}

fn строка_метки(метка: &[u8; 11]) -> &str {
    let конец = метка.iter().rposition(|&b| b != b' ' && b != 0).map(|i| i + 1).unwrap_or(0);
    core::str::from_utf8(&метка[..конец]).unwrap_or("?")
}

// ==========================================================================
// Перечисление корневого каталога (8.3 имена)
// ==========================================================================

#[repr(C, packed)]
#[derive(Clone, Copy)]
struct ЗаписьDIR {
    name: [u8; 11],
    attr: u8,
    nt_reserved: u8,
    ctime_tenth: u8,
    ctime: u16,
    cdate: u16,
    adate: u16,
    cluster_high: u16,
    mtime: u16,
    mdate: u16,
    cluster_low: u16,
    file_size: u32,
}

const ATTR_RO: u8 = 0x01;
const ATTR_HIDDEN: u8 = 0x02;
const ATTR_SYSTEM: u8 = 0x04;
const ATTR_VOLID: u8 = 0x08;
const ATTR_DIR: u8 = 0x10;
const ATTR_ARCHIVE: u8 = 0x20;
const ATTR_LFN: u8 = ATTR_RO | ATTR_HIDDEN | ATTR_SYSTEM | ATTR_VOLID;

#[derive(Debug, Clone, Copy)]
pub struct ЗаписьФайла {
    pub имя_8_3: [u8; 12],   // "FOO     BAR" → "FOO.BAR"
    pub каталог: bool,
    pub размер: u32,
    pub первый_кластер: u32,
}

impl ЗаписьФайла {
    pub fn имя(&self) -> &str {
        let конец = self.имя_8_3.iter().position(|&b| b == 0).unwrap_or(self.имя_8_3.len());
        core::str::from_utf8(&self.имя_8_3[..конец]).unwrap_or("?")
    }
}

/// Перечислить файлы в корневом каталоге, вызывая `выдать` для каждого.
pub fn перечислить_корень(
    хба: &ахки::ИнфоAHCI,
    порт: u8,
    том: &ТомFAT32,
    mut выдать: impl FnMut(&ЗаписьФайла) -> bool,
) {
    let mut кластер = том.корневой_кластер;
    while кластер >= 2 && кластер < 0x0FFF_FFF8 {
        if !читать_кластер(хба, порт, том, кластер, &mut выдать) {
            return;
        }
        кластер = match следующий_кластер(хба, порт, том, кластер) {
            Some(c) => c,
            None => return,
        };
    }
}

/// Вернуть номер следующего кластера в цепочке через FAT.
fn следующий_кластер(
    хба: &ахки::ИнфоAHCI,
    порт: u8,
    том: &ТомFAT32,
    кластер: u32,
) -> Option<u32> {
    let оффсет_в_fat = (кластер as u64) * 4;
    let сектор_fat = том.лба_fat + оффсет_в_fat / том.байт_в_секторе as u64;
    let оффсет_в_секторе = (оффсет_в_fat % том.байт_в_секторе as u64) as u32;
    let phys = ахки::прочитать_секторы(хба, порт, сектор_fat, 1)?;
    let значение = unsafe {
        core::ptr::read_unaligned((phys + оффсет_в_секторе as u64) as *const u32) & 0x0FFF_FFFF
    };
    let _ = crate::память::физическая::АЛЛОКАТОР
        .lock()
        .освободить_страницу(crate::память::ФизАдрес::новый(phys));
    Some(значение)
}

/// Прочитать один кластер каталога и вызвать `выдать` для каждой записи.
fn читать_кластер(
    хба: &ахки::ИнфоAHCI,
    порт: u8,
    том: &ТомFAT32,
    кластер: u32,
    выдать: &mut impl FnMut(&ЗаписьФайла) -> bool,
) -> bool {
    // Кластер может быть до 64 КиБ, но мы поддерживаем до 4 КиБ за раз.
    let секторов_за_раз: u16 = том.секторов_в_кластере.min(8) as u16;
    if секторов_за_раз == 0 { return true; }
    let лба_кластера = том.кластер_lba(кластер);
    let phys = match ахки::прочитать_секторы(хба, порт, лба_кластера, секторов_за_раз) {
        Some(p) => p,
        None => return true,
    };
    let длина = (секторов_за_раз as u32) * том.байт_в_секторе;
    let записей = длина / 32;
    let mut продолжать = true;
    for i in 0..записей {
        let з: ЗаписьDIR = unsafe {
            core::ptr::read_unaligned((phys + (i * 32) as u64) as *const ЗаписьDIR)
        };
        if з.name[0] == 0 { продолжать = false; break; }
        if з.name[0] == 0xE5 { continue; }    // удалена
        if з.attr & ATTR_VOLID != 0 && з.attr & ATTR_LFN != ATTR_LFN { continue; }
        if (з.attr & ATTR_LFN) == ATTR_LFN { continue; } // LFN-фрагмент

        let mut имя = [0u8; 12];
        // Базовое имя: 8 символов без хвостовых пробелов.
        let mut вых = 0;
        for k in 0..8 {
            if з.name[k] == b' ' { break; }
            имя[вых] = з.name[k];
            вых += 1;
        }
        // Расширение: 3 символа после точки.
        let мест = (8..11).filter(|&k| з.name[k] != b' ').count();
        if мест > 0 {
            имя[вых] = b'.';
            вых += 1;
            for k in 8..11 {
                if з.name[k] == b' ' { break; }
                if вых < имя.len() {
                    имя[вых] = з.name[k];
                    вых += 1;
                }
            }
        }
        let файл = ЗаписьФайла {
            имя_8_3: имя,
            каталог: з.attr & ATTR_DIR != 0,
            размер: з.file_size,
            первый_кластер: ((з.cluster_high as u32) << 16) | (з.cluster_low as u32),
        };
        if !выдать(&файл) { продолжать = false; break; }
    }
    let _ = crate::память::физическая::АЛЛОКАТОР
        .lock()
        .освободить_страницу(crate::память::ФизАдрес::новый(phys));
    продолжать
}

/// Распечатать корень тома. Полезно как самотест.
pub fn распечатать_корень(хба: &ахки::ИнфоAHCI, порт: u8, том: &ТомFAT32) {
    let mut счётчик = 0u32;
    println!("[ИНФО] FAT32 / : содержимое корневого каталога");
    перечислить_корень(хба, порт, том, |файл| {
        счётчик += 1;
        if счётчик > 32 { return false; }
        let метка = if файл.каталог { "DIR" } else { "FILE" };
        println!(
            "       {:<12} {:>8} байт  кластер {}  ({})",
            файл.имя(), файл.размер, файл.первый_кластер, метка
        );
        true
    });
    println!("[OK]   FAT32 / : всего записей {}", счётчик);
}
