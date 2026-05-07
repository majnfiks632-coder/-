// ДубинаОС
// Авторские права (C) 2026, Команда ДубинаОС
// Лицензия: GPL-3.0-or-later
//
// Архитектурно-зависимый код для aarch64 (ARMv8). Скелет — будет дорабатываться в
// отдельных этапах дорожной карты.

#[path = "uart_pl011.rs"]
pub mod uart_pl011;

use crate::печать::НосительПечати;
use core::arch::global_asm;

// Подключаем ассемблерную точку входа aarch64.
global_asm!(include_str!("вход.s"));

pub fn ранняя_инициализация() {
    uart_pl011::инициализировать();
}

pub fn с_носителями_печати<Ф: FnMut(&mut dyn НосительПечати)>(mut ф: Ф) {
    let мутекс = uart_pl011::порт();
    let mut порт = мутекс.lock();
    ф(&mut *порт);
}

pub fn халт_навсегда() -> ! {
    loop {
        unsafe {
            core::arch::asm!("wfi", options(nomem, nostack, preserves_flags));
        }
    }
}
