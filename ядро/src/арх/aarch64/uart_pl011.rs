// ДубинаОС
// Авторские права (C) 2026, Команда ДубинаОС
// Лицензия: GPL-3.0-or-later
//
// Драйвер UART PL011 (ARM PrimeCell). Используется QEMU virt-машиной по адресу 0x09000000.

use core::ptr;
use spin::Mutex;

use crate::печать::НосительПечати;

const ОСНОВА: usize = 0x0900_0000;
const UARTDR: usize = 0x000;
const UARTFR: usize = 0x018;

static ПОРТ: Mutex<Pl011> = Mutex::new(Pl011 { готов: false });

pub fn порт() -> &'static Mutex<Pl011> {
    &ПОРТ
}

pub fn инициализировать() {
    ПОРТ.lock().готов = true;
}

pub struct Pl011 {
    готов: bool,
}

impl Pl011 {
    fn записать_байт(&mut self, байт: u8) {
        if !self.готов {
            return;
        }
        unsafe {
            // Ждём, пока FR.TXFF = 0
            while (ptr::read_volatile((ОСНОВА + UARTFR) as *const u32) & (1 << 5)) != 0 {}
            ptr::write_volatile((ОСНОВА + UARTDR) as *mut u32, байт as u32);
        }
    }
}

impl НосительПечати for Pl011 {
    fn записать_строку(&mut self, текст: &str) {
        for б in текст.bytes() {
            if б == b'\n' {
                self.записать_байт(b'\r');
            }
            self.записать_байт(б);
        }
    }
}
