// ДубинаОС
// Авторские права (C) 2026, Команда ДубинаОС
// Лицензия: GPL-3.0-or-later
//
// Минимальный рантайм языка «Дубина». Здесь определяются типы, которые скомпилированный
// код использует во время выполнения, в первую очередь — динамическое целое `Число`.

#![no_std]
#![allow(uncommon_codepoints, mixed_script_confusables, confusable_idents, non_snake_case)]

extern crate alloc;

#[path = "число.rs"]
pub mod число;
pub use число::Число;
