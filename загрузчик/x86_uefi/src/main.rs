// ДубинаОС
// Авторские права (C) 2026, Команда ДубинаОС
// Лицензия: GPL-3.0-or-later
//
// UEFI-загрузчик ДубинаОС.
//
// Что делает:
//   1. Открывает ESP (раздел, с которого UEFI запустил BOOTX64.EFI).
//   2. Читает файл `\yadro.elf` — ядро в формате ELF64.
//   3. Парсит программные заголовки (PT_LOAD), выделяет страницы по
//      физическим адресам, копирует сегменты, обнуляет BSS.
//   4. Парсит таблицу символов и ищет `kernel_entry_uefi`.
//   5. Получает framebuffer через GraphicsOutputProtocol.
//   6. Ищет RSDP в UEFI Configuration Table (ACPI 2.0, потом 1.0).
//   7. Строит ИнфоЗагрузки в LoaderData-странице.
//   8. Вызывает ExitBootServices, формирует карту памяти.
//   9. Прыгает в `kernel_entry_uefi(МАГИЯ_ДУБ_ПРОТО, &инфо)`.

#![no_main]
#![no_std]
#![allow(uncommon_codepoints)]

extern crate alloc;

use alloc::vec::Vec;
use core::mem::size_of;
use core::slice;
use uefi::prelude::*;
use uefi::println;
use uefi::proto::console::gop::{GraphicsOutput, PixelFormat};
use uefi::proto::media::file::{File, FileAttribute, FileMode, FileType, RegularFile};
use uefi::table::boot::{
    AllocateType, MemoryType, OpenProtocolAttributes, OpenProtocolParams,
};
use uefi::table::cfg;
use uefi::CStr16;

// ---------- Дублируем структуры протокола, чтобы не зависеть от ядра ----------
//
// Должно один-в-один совпадать с `ядро/src/инфо_загрузки.rs`.

const МАГИЯ_ДУБ_ПРОТО: u64 = 0xD0BA_0BE5_DBBA_0001;
const ВЕРСИЯ_ПРОТОКОЛА: u32 = 1;
const ИСТОЧНИК_UEFI: u32 = 1;

const ОБЛАСТЬ_ДОСТУПНА: u32 = 1;
const ОБЛАСТЬ_ЗАРЕЗЕРВИРОВАНА: u32 = 2;
const ОБЛАСТЬ_ACPI_ВОССТАНОВИМАЯ: u32 = 3;
const ОБЛАСТЬ_ACPI_NVS: u32 = 4;
const ОБЛАСТЬ_ПЛОХАЯ: u32 = 5;

const ФБ_ФОРМАТ_RGB: u32 = 1;
const ФБ_ФОРМАТ_BGR: u32 = 2;
const ФБ_ФОРМАТ_МАСКА: u32 = 3;
const ФБ_ФОРМАТ_BLT_ONLY: u32 = 4;

#[repr(C, align(8))]
#[derive(Clone, Copy)]
struct ОбластьПамяти {
    начало: u64,
    длина: u64,
    тип: u32,
    _резерв: u32,
}

#[repr(C, align(8))]
#[derive(Clone, Copy)]
struct ИнфоЗагрузки {
    магия: u64,
    версия: u32,
    размер: u32,

    источник: u32,
    _резерв1: u32,

    фб_адрес: u64,
    фб_размер: u64,
    фб_ширина: u32,
    фб_высота: u32,
    фб_шаг_пикселей: u32,
    фб_бит_на_пиксель: u32,
    фб_формат: u32,
    _резерв_фб: u32,

    карта_памяти: u64,
    карта_памяти_элементов: u32,
    карта_памяти_размер_элемента: u32,

    rsdp: u64,
    ядро_физический: u64,
    ядро_длина: u64,
}

// ---------- ELF64 структуры ----------

#[repr(C)]
struct Elf64Ehdr {
    e_ident: [u8; 16],
    e_type: u16,
    e_machine: u16,
    e_version: u32,
    e_entry: u64,
    e_phoff: u64,
    e_shoff: u64,
    e_flags: u32,
    e_ehsize: u16,
    e_phentsize: u16,
    e_phnum: u16,
    e_shentsize: u16,
    e_shnum: u16,
    e_shstrndx: u16,
}

#[repr(C)]
struct Elf64Phdr {
    p_type: u32,
    p_flags: u32,
    p_offset: u64,
    p_vaddr: u64,
    p_paddr: u64,
    p_filesz: u64,
    p_memsz: u64,
    p_align: u64,
}

#[repr(C)]
struct Elf64Shdr {
    sh_name: u32,
    sh_type: u32,
    sh_flags: u64,
    sh_addr: u64,
    sh_offset: u64,
    sh_size: u64,
    sh_link: u32,
    sh_info: u32,
    sh_addralign: u64,
    sh_entsize: u64,
}

#[repr(C)]
struct Elf64Sym {
    st_name: u32,
    st_info: u8,
    st_other: u8,
    st_shndx: u16,
    st_value: u64,
    st_size: u64,
}

const PT_LOAD: u32 = 1;
const SHT_SYMTAB: u32 = 2;
const SHT_STRTAB: u32 = 3;

// ---------- Точка входа UEFI ----------

#[entry]
fn главная(_образ: Handle, mut услуги: SystemTable<Boot>) -> Status {
    uefi::helpers::init(&mut услуги).expect("init UEFI helpers");

    println!();
    println!("==============================================");
    println!("       Загрузчик ДубинаОС (UEFI x86_64)       ");
    println!("              ДубПрото v1                     ");
    println!("==============================================");
    println!();

    // ---- 1. Прочитать `\yadro.elf` с ESP. ----
    let байты_ядра = match прочитать_ядро(&услуги) {
        Ok(буфер) => буфер,
        Err(статус) => {
            println!("[ОШИБКА] Не удалось прочитать ядро: {:?}", статус);
            return статус;
        }
    };
    println!("[OK] Ядро прочитано: {} байт", байты_ядра.len());

    // ---- 2. Загрузить ELF в физическую память. ----
    let (адрес_ядра, длина_ядра, точка_входа) = match загрузить_elf(&услуги, &байты_ядра)
    {
        Ok(данные) => данные,
        Err(статус) => {
            println!("[ОШИБКА] ELF-загрузка: {:?}", статус);
            return статус;
        }
    };
    println!(
        "[OK] ELF загружен: 0x{:X}..0x{:X}, точка входа kernel_entry_uefi = 0x{:X}",
        адрес_ядра,
        адрес_ядра + длина_ядра,
        точка_входа
    );

    // ---- 3. Получить framebuffer ----
    println!("[CK1] получаем GOP framebuffer…");
    let (фб_адрес, фб_размер, фб_ширина, фб_высота, фб_шаг, фб_бпп, фб_формат) =
        match получить_framebuffer(&услуги) {
            Some(данные) => данные,
            None => (0, 0, 0, 0, 0, 0, 0),
        };
    println!("[CK2] GOP получен (адрес=0x{:X})", фб_адрес);
    if фб_адрес != 0 {
        println!(
            "[OK] Framebuffer: {}x{} @ 0x{:X}, шаг {} пикс., {} бит, формат={}",
            фб_ширина, фб_высота, фб_адрес, фб_шаг, фб_бпп, фб_формат
        );
    } else {
        println!("[ВНИМАНИЕ] Framebuffer не получен — ядро останется на serial");
    }

    // ---- 4. Найти RSDP в UEFI configuration table ----
    println!("[CK3] ищем RSDP…");
    let rsdp = найти_rsdp(&услуги);
    println!("[CK4] RSDP=0x{:X}", rsdp);
    if rsdp != 0 {
        println!("[OK] ACPI RSDP: 0x{:X}", rsdp);
    } else {
        println!("[ВНИМАНИЕ] RSDP не найден");
    }

    // ---- 5. Заранее выделить страницу под ИнфоЗагрузки и под массив областей. ----
    // Сначала узнаём сколько примерно дескрипторов в memory map (UEFI обычно 80–200).
    // Берём с большим запасом — 512 областей хватит для любых машин.
    const МАКС_ОБЛАСТЕЙ: usize = 512;
    let байт_под_инфо = (size_of::<ИнфоЗагрузки>() + 0xFFF) & !0xFFF;
    let страниц_под_инфо = байт_под_инфо / 0x1000;
    let байт_под_карту = МАКС_ОБЛАСТЕЙ * size_of::<ОбластьПамяти>();
    let страниц_под_карту = (байт_под_карту + 0xFFF) / 0x1000;

    let стр_инфо = match услуги.boot_services().allocate_pages(
        // Гарантируем, что эти структуры лежат ниже 4 ГиБ — наша начальная
        // PML4 (см. вход_uefi.s) идентично маппит только 0..4 ГиБ. На
        // некоторых UEFI-прошивках AllocateType::AnyPages может вернуть
        // адрес выше 4 ГиБ — тогда ядро #PF'ит при первом же чтении.
        AllocateType::MaxAddress(0xFFFF_FFFF),
        MemoryType::LOADER_DATA,
        страниц_под_инфо,
    ) {
        Ok(а) => а,
        Err(_) => return Status::OUT_OF_RESOURCES,
    };
    let стр_карта = match услуги.boot_services().allocate_pages(
        AllocateType::MaxAddress(0xFFFF_FFFF),
        MemoryType::LOADER_DATA,
        страниц_под_карту,
    ) {
        Ok(а) => а,
        Err(_) => return Status::OUT_OF_RESOURCES,
    };

    // Заранее заполняем «общие» поля ИнфоЗагрузки. Карту памяти заполним
    // уже после ExitBootServices.
    let инфо_указатель = стр_инфо as *mut ИнфоЗагрузки;
    unsafe {
        core::ptr::write(
            инфо_указатель,
            ИнфоЗагрузки {
                магия: МАГИЯ_ДУБ_ПРОТО,
                версия: ВЕРСИЯ_ПРОТОКОЛА,
                размер: size_of::<ИнфоЗагрузки>() as u32,
                источник: ИСТОЧНИК_UEFI,
                _резерв1: 0,
                фб_адрес,
                фб_размер: фб_размер as u64,
                фб_ширина,
                фб_высота,
                фб_шаг_пикселей: фб_шаг,
                фб_бит_на_пиксель: фб_бпп,
                фб_формат,
                _резерв_фб: 0,
                карта_памяти: стр_карта,
                карта_памяти_элементов: 0, // заполним ниже
                карта_памяти_размер_элемента: size_of::<ОбластьПамяти>() as u32,
                rsdp,
                ядро_физический: адрес_ядра,
                ядро_длина: длина_ядра,
            },
        );
    }

    println!("[ИНФО] Выход из Boot Services и переход в ядро…");
    // Сбросим watchdog чтоб UEFI не перезагрузил машину.
    let _ = услуги.boot_services().set_watchdog_timer(0, 0x10000, None);

    // ---- 6. Exit boot services. ----
    let (_runtime, карта) = unsafe { услуги.exit_boot_services(MemoryType::LOADER_DATA) };

    // ---- 7. Сконвертировать UEFI memory map → ОбластьПамяти. ----
    let mut число_областей: usize = 0;
    let базовая_карта = стр_карта as *mut ОбластьПамяти;
    for дескриптор in карта.entries() {
        if число_областей >= МАКС_ОБЛАСТЕЙ {
            break;
        }
        let тип = соответствие_типа(дескриптор.ty);
        let область = ОбластьПамяти {
            начало: дескриптор.phys_start,
            длина: дескриптор.page_count * 4096,
            тип,
            _резерв: 0,
        };
        unsafe {
            core::ptr::write(базовая_карта.add(число_областей), область);
        }
        число_областей += 1;
    }
    unsafe {
        (*инфо_указатель).карта_памяти_элементов = число_областей as u32;
    }

    // ---- 8. Прыгаем в kernel_entry_uefi ----
    //
    // Ядро собрано для x86_64-unknown-none → System V x86_64 ABI:
    //   RDI = магия, RSI = указатель на ИнфоЗагрузки.
    //
    // А мы сами в x86_64-unknown-uefi → Microsoft x64 ABI:
    //   RCX = первый аргумент, RDX = второй и т.д.
    //
    // Поэтому объявляем тип указателя как `extern "sysv64"`, чтобы Rust
    // выложил аргументы в RDI/RSI, как ожидает kernel_entry_uefi.
    let kernel_entry: extern "sysv64" fn(u64, u64) -> ! =
        unsafe { core::mem::transmute(точка_входа) };
    kernel_entry(МАГИЯ_ДУБ_ПРОТО, стр_инфо);
}

// ---------------- Чтение `\yadro.elf` с ESP ----------------

fn прочитать_ядро(услуги: &SystemTable<Boot>) -> Result<Vec<u8>, Status> {
    let bs = услуги.boot_services();
    // image_handle глобально установлен через uefi::helpers::init().
    let image_handle = bs.image_handle();
    let mut фс = bs
        .get_image_file_system(image_handle)
        .map_err(|_| Status::NOT_FOUND)?;

    let mut корень = фс.open_volume().map_err(|_| Status::NOT_FOUND)?;
    let mut буфер_имени = [0u16; 32];
    let имя = CStr16::from_str_with_buf("\\yadro.elf", &mut буфер_имени)
        .map_err(|_| Status::INVALID_PARAMETER)?;

    let дескр = корень
        .open(имя, FileMode::Read, FileAttribute::empty())
        .map_err(|_| Status::NOT_FOUND)?;
    let mut файл: RegularFile = match дескр.into_type().map_err(|_| Status::NOT_FOUND)? {
        FileType::Regular(р) => р,
        FileType::Dir(_) => return Err(Status::INVALID_PARAMETER),
    };

    // Узнаём размер. UEFI требует выровненного буфера под FileInfo —
    // используем стандартный helper.
    let mut небольшой = [0u8; 512];
    let info = match файл.get_info::<uefi::proto::media::file::FileInfo>(&mut небольшой) {
        Ok(i) => i,
        Err(err) if err.status() == Status::BUFFER_TOO_SMALL => {
            // Размер запрошенного буфера лежит в данных ошибки.
            // Аллоцируем кучей через alloc::vec (UEFI helpers подключили global allocator).
            let размер_буфера = err.data().unwrap_or(0);
            let mut большой = alloc::vec![0u8; размер_буфера];
            файл.get_info::<uefi::proto::media::file::FileInfo>(&mut большой)
                .map_err(|_| Status::NOT_FOUND)?;
            // Перепарсим: ниже мы только используем file_size.
            // Чтоб не возиться с временными буферами вне scope, читаем напрямую.
            let _ = большой;
            // FileInfo живёт ровно столько, сколько буфер. Прочитаем размер
            // повторно со свежим буфером того же размера.
            let mut буф2 = alloc::vec![0u8; размер_буфера];
            let i2 = файл
                .get_info::<uefi::proto::media::file::FileInfo>(&mut буф2)
                .map_err(|_| Status::NOT_FOUND)?;
            let размер = i2.file_size() as usize;
            return дочитать_файл(&mut файл, размер);
        }
        Err(_) => return Err(Status::NOT_FOUND),
    };
    let размер = info.file_size() as usize;
    дочитать_файл(&mut файл, размер)
}

fn дочитать_файл(файл: &mut RegularFile, размер: usize) -> Result<Vec<u8>, Status> {
    let mut буфер = alloc::vec![0u8; размер];
    let прочитано = файл.read(&mut буфер).map_err(|_| Status::NOT_FOUND)?;
    if прочитано < размер {
        буфер.truncate(прочитано);
    }
    Ok(буфер)
}

// ---------------- Загрузка ELF ----------------

fn загрузить_elf(услуги: &SystemTable<Boot>, байты: &[u8]) -> Result<(u64, u64, u64), Status> {
    if байты.len() < size_of::<Elf64Ehdr>() {
        return Err(Status::INVALID_PARAMETER);
    }
    // Магия ELF и проверка архитектуры/класса.
    if &байты[0..4] != b"\x7FELF" {
        return Err(Status::INVALID_PARAMETER);
    }
    if байты[4] != 2 || байты[5] != 1 {
        // ELF64, little endian.
        return Err(Status::INVALID_PARAMETER);
    }

    let ehdr = unsafe { &*(байты.as_ptr() as *const Elf64Ehdr) };
    if ehdr.e_machine != 62 {
        // 62 = EM_X86_64
        return Err(Status::INVALID_PARAMETER);
    }
    if (ehdr.e_phentsize as usize) < size_of::<Elf64Phdr>() {
        return Err(Status::INVALID_PARAMETER);
    }

    let bs = услуги.boot_services();

    // Найти диапазон [мин..мах] всех PT_LOAD сегментов по физическим адресам.
    let mut мин: u64 = u64::MAX;
    let mut мах: u64 = 0;
    for i in 0..ehdr.e_phnum as usize {
        let смещ = ehdr.e_phoff as usize + i * ehdr.e_phentsize as usize;
        if смещ + size_of::<Elf64Phdr>() > байты.len() {
            return Err(Status::INVALID_PARAMETER);
        }
        let phdr = unsafe { &*(байты.as_ptr().add(смещ) as *const Elf64Phdr) };
        if phdr.p_type == PT_LOAD && phdr.p_memsz != 0 {
            мин = мин.min(phdr.p_paddr);
            мах = мах.max(phdr.p_paddr + phdr.p_memsz);
        }
    }
    if мин == u64::MAX {
        return Err(Status::INVALID_PARAMETER);
    }

    // Выравниваем на 4 КиБ.
    let начало = мин & !0xFFF;
    let конец = (мах + 0xFFF) & !0xFFF;
    let страниц = ((конец - начало) / 4096) as usize;

    // Пытаемся выделить именно по адресу LMA. Если UEFI занят чем-то на 1 МиБ
    // (редко — обычно EfiConventionalMemory), падаем — в этом этапе делать
    // позиционно-независимое ядро не стоит.
    let физ = bs
        .allocate_pages(
            AllocateType::Address(начало),
            MemoryType::LOADER_DATA,
            страниц,
        )
        .map_err(|_| Status::OUT_OF_RESOURCES)?;
    if физ != начало {
        return Err(Status::OUT_OF_RESOURCES);
    }

    // Обнуляем выделенные страницы.
    unsafe {
        core::ptr::write_bytes(начало as *mut u8, 0, (конец - начало) as usize);
    }

    // Копируем сегменты.
    for i in 0..ehdr.e_phnum as usize {
        let смещ = ehdr.e_phoff as usize + i * ehdr.e_phentsize as usize;
        let phdr = unsafe { &*(байты.as_ptr().add(смещ) as *const Elf64Phdr) };
        if phdr.p_type != PT_LOAD || phdr.p_memsz == 0 {
            continue;
        }
        // Копируем p_filesz байт из файла на p_paddr.
        let конец_файла = phdr.p_offset as usize + phdr.p_filesz as usize;
        if конец_файла > байты.len() {
            return Err(Status::INVALID_PARAMETER);
        }
        let источник = &байты[phdr.p_offset as usize..конец_файла];
        let назначение = unsafe {
            slice::from_raw_parts_mut(phdr.p_paddr as *mut u8, phdr.p_filesz as usize)
        };
        назначение.copy_from_slice(источник);
        // Остаток (BSS) уже обнулён write_bytes выше.
    }

    // Найти symbol `kernel_entry_uefi`.
    let точка_входа = match найти_символ(байты, "kernel_entry_uefi") {
        Some(адрес) => адрес,
        None => {
            // Запасной вариант: использовать e_entry — но там 32-битный код.
            return Err(Status::NOT_FOUND);
        }
    };

    Ok((начало, конец - начало, точка_входа))
}

fn найти_символ(байты: &[u8], имя: &str) -> Option<u64> {
    if байты.len() < size_of::<Elf64Ehdr>() {
        return None;
    }
    let ehdr = unsafe { &*(байты.as_ptr() as *const Elf64Ehdr) };
    let shoff = ehdr.e_shoff as usize;
    let shentsize = ehdr.e_shentsize as usize;
    let shnum = ehdr.e_shnum as usize;
    if shoff + shnum * shentsize > байты.len() {
        return None;
    }

    // Найти SHT_SYMTAB.
    let mut symtab: Option<&Elf64Shdr> = None;
    for i in 0..shnum {
        let смещ = shoff + i * shentsize;
        let sh = unsafe { &*(байты.as_ptr().add(смещ) as *const Elf64Shdr) };
        if sh.sh_type == SHT_SYMTAB {
            symtab = Some(sh);
            break;
        }
    }
    let symtab = symtab?;
    let strtab_idx = symtab.sh_link as usize;
    if strtab_idx >= shnum {
        return None;
    }
    let strtab_sh = unsafe { &*(байты.as_ptr().add(shoff + strtab_idx * shentsize) as *const Elf64Shdr) };
    if strtab_sh.sh_type != SHT_STRTAB {
        return None;
    }
    let strtab_начало = strtab_sh.sh_offset as usize;
    let strtab_конец = strtab_начало + strtab_sh.sh_size as usize;
    if strtab_конец > байты.len() {
        return None;
    }
    let strtab = &байты[strtab_начало..strtab_конец];

    // Перебрать символы.
    let entsize = symtab.sh_entsize as usize;
    if entsize < size_of::<Elf64Sym>() {
        return None;
    }
    let n = (symtab.sh_size as usize) / entsize;
    for i in 0..n {
        let смещ = symtab.sh_offset as usize + i * entsize;
        if смещ + size_of::<Elf64Sym>() > байты.len() {
            break;
        }
        let sym = unsafe { &*(байты.as_ptr().add(смещ) as *const Elf64Sym) };
        if sym.st_name == 0 {
            continue;
        }
        let имя_начало = sym.st_name as usize;
        if имя_начало >= strtab.len() {
            continue;
        }
        // Длина строки до '\0'.
        let mut длина = 0usize;
        while имя_начало + длина < strtab.len() && strtab[имя_начало + длина] != 0 {
            длина += 1;
        }
        let символ_имя = match core::str::from_utf8(&strtab[имя_начало..имя_начало + длина]) {
            Ok(с) => с,
            Err(_) => continue,
        };
        if символ_имя == имя {
            return Some(sym.st_value);
        }
    }
    None
}

// ---------------- GOP framebuffer ----------------

fn получить_framebuffer(
    услуги: &SystemTable<Boot>,
) -> Option<(u64, usize, u32, u32, u32, u32, u32)> {
    let bs = услуги.boot_services();
    let handle = match bs.get_handle_for_protocol::<GraphicsOutput>() {
        Ok(h) => h,
        Err(_) => {
            println!("  (FB) GOP-handle не найден");
            return None;
        }
    };
    // ВАЖНО: на ASUS X552EA (AMI/Phoenix CSM-firmware 2013 г.)
    // open_protocol_exclusive на GraphicsOutput **зависает** прошивку:
    // флаг BY_EXCLUSIVE требует от прошивки прервать всех других «агентов»
    // (включая саму ConsoleOut-консоль), и на этой версии firmware это
    // приводит к зависанию.
    //
    // Используем НЕэксклюзивный вариант — `GetProtocol`. Это безопасно для
    // нашего сценария: мы только читаем mode info и адрес framebuffer'а
    // и не собираемся ничего «эксклюзивно держать».
    let params = OpenProtocolParams {
        handle,
        agent: bs.image_handle(),
        controller: None,
    };
    let mut gop = match unsafe {
        bs.open_protocol::<GraphicsOutput>(params, OpenProtocolAttributes::GetProtocol)
    } {
        Ok(g) => g,
        Err(ошибка) => {
            println!("  (FB) open_protocol(GetProtocol): {:?}", ошибка.status());
            return None;
        }
    };
    let info = gop.current_mode_info();
    let (ширина, высота) = info.resolution();
    let шаг = info.stride() as u32;
    let формат = match info.pixel_format() {
        PixelFormat::Rgb => ФБ_ФОРМАТ_RGB,
        PixelFormat::Bgr => ФБ_ФОРМАТ_BGR,
        PixelFormat::Bitmask => ФБ_ФОРМАТ_МАСКА,
        PixelFormat::BltOnly => ФБ_ФОРМАТ_BLT_ONLY,
    };
    if формат == ФБ_ФОРМАТ_BLT_ONLY {
        println!("  (FB) формат BLT-only — нельзя писать прямо в память");
        return None;
    }
    let mut фб = gop.frame_buffer();
    let адрес = фб.as_mut_ptr() as u64;
    let размер = фб.size();
    Some((адрес, размер, ширина as u32, высота as u32, шаг, 32, формат))
}

// ---------------- ACPI RSDP ----------------

fn найти_rsdp(услуги: &SystemTable<Boot>) -> u64 {
    let таблица = услуги.config_table();
    // Сначала ищем ACPI 2.0+, потом 1.0.
    for запись in таблица {
        if запись.guid == cfg::ACPI2_GUID {
            return запись.address as u64;
        }
    }
    for запись in таблица {
        if запись.guid == cfg::ACPI_GUID {
            return запись.address as u64;
        }
    }
    0
}

// ---------------- Перевод UEFI-типа в наш ОБЛАСТЬ_* ----------------

fn соответствие_типа(тип: MemoryType) -> u32 {
    match тип {
        // EfiConventionalMemory.
        MemoryType::CONVENTIONAL => ОБЛАСТЬ_ДОСТУПНА,
        // BootServicesCode/Data — после ExitBootServices их можно
        // использовать как обычную память (по UEFI-спецификации).
        MemoryType::BOOT_SERVICES_CODE | MemoryType::BOOT_SERVICES_DATA => {
            ОБЛАСТЬ_ДОСТУПНА
        }
        // Loader-память — используется самим загрузчиком (наше ядро,
        // ИнфоЗагрузки, ELF-копия и пр.) — НЕ помечаем как доступную.
        MemoryType::LOADER_CODE | MemoryType::LOADER_DATA => ОБЛАСТЬ_ЗАРЕЗЕРВИРОВАНА,
        // ACPI.
        MemoryType::ACPI_RECLAIM => ОБЛАСТЬ_ACPI_ВОССТАНОВИМАЯ,
        MemoryType::ACPI_NON_VOLATILE => ОБЛАСТЬ_ACPI_NVS,
        // Все остальные — резерв или плохая.
        MemoryType::UNUSABLE => ОБЛАСТЬ_ПЛОХАЯ,
        _ => ОБЛАСТЬ_ЗАРЕЗЕРВИРОВАНА,
    }
}
