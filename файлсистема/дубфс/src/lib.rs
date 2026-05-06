// ДубинаОС
// Авторские права (C) 2026, Команда ДубинаОС
// Лицензия: GPL-3.0-or-later
//
// ДубФС — журналируемая файловая система ДубинаОС.
//
// Полное описание формата — в `документация/ДУБФС.md`. Этот крейт реализует
// его как единый no_std + alloc модуль, который встраивается и в ядро,
// и в пользовательскую утилиту `мкдубфс`. Тесты прогоняются на хосте через
// std (cargo test).

#![no_std]
#![allow(uncommon_codepoints)]
#![cfg_attr(test, allow(unused_imports))]
#![deny(unsafe_op_in_unsafe_fn)]

extern crate alloc;
#[cfg(test)]
extern crate std;

#[path = "битмап.rs"]
pub mod битмап;
#[path = "блок.rs"]
pub mod блок;
#[path = "каталог.rs"]
pub mod каталог;
#[path = "контрольная_сумма.rs"]
pub mod контрольная_сумма;
#[path = "журнал.rs"]
pub mod журнал;
#[path = "инод.rs"]
pub mod инод;
#[path = "ошибки.rs"]
pub mod ошибки;
#[path = "суперблок.rs"]
pub mod суперблок;
#[path = "фс.rs"]
pub mod фс;

pub use ошибки::{ОшибкаФС, Резюме};
pub use фс::{ДубФС, ПараметрыСоздания, Тип, Статистика};
