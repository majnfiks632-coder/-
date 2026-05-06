// ДубинаОС
// Авторские права (C) 2026, Команда ДубинаОС
// Лицензия: GPL-3.0-or-later
//
// UEFI-загрузчик ДубинаОС.
//
// Этап «Фундамент»: минимальное приложение UEFI, выводящее заголовок и приветствие.
// Загрузка ядра из ESP, передача управления — следующий этап.

#![no_main]
#![no_std]
#![allow(uncommon_codepoints)]

use uefi::prelude::*;
use uefi::println;

#[entry]
fn главная(_образ: Handle, mut услуги: SystemTable<Boot>) -> Status {
    uefi::helpers::init(&mut услуги).expect("ошибка инициализации UEFI helpers");

    println!();
    println!("==============================================");
    println!("       Загрузчик ДубинаОС (UEFI x86_64)       ");
    println!("              Версия 0.1.0                    ");
    println!("==============================================");
    println!();
    println!("[OK] UEFI Boot Services инициализированы");
    println!("[ИНФО] Версия таблицы UEFI: {:?}", услуги.uefi_revision());
    println!();
    println!("Загрузка ядра ДубинаОС будет реализована в этапе 2.");
    println!("Сейчас просто подтверждаем успешный запуск UEFI-приложения.");
    println!();

    // Пауза 5 секунд, потом возвращаемся в UEFI.
    услуги.boot_services().stall(5_000_000);

    Status::SUCCESS
}
