// ДубинаОС
// Авторские права (C) 2026, Команда ДубинаОС
// Лицензия: GPL-3.0-or-later
//
// Драйвер PS/2-клавиатуры (контроллер 8042).
//
// Подключается к каркасу через `trait Драйвер`, привязывается к IRQ1.
// При срабатывании прерывания читает байт сканкода из порта 0x60, переводит
// его в `Клавиша` (с учётом Shift / Ctrl / Alt), и кладёт в очередь.
//
// Очередь читают потребители (отладочное окно, оболочка и т.д.).
//
// Все идентификаторы и сообщения — на русском.

#![allow(dead_code)]

use core::sync::atomic::{AtomicBool, Ordering};
use spin::Mutex;

use super::Драйвер;

/// Простая FIFO-очередь нажатий клавиш ёмкостью 64 события.
const ЁМКОСТЬ_ОЧЕРЕДИ: usize = 64;

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum Клавиша {
    Символ(char),
    Ввод,
    ПробелТабуляции,
    Возврат,
    Стрелка(Стрелка),
    F(u8),
    Esc,
    PgUp,
    PgDn,
    Home,
    End,
    Неизвестная(u8),
}

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum Стрелка {
    Влево,
    Вправо,
    Вверх,
    Вниз,
}

struct Состояние {
    очередь: [Option<Клавиша>; ЁМКОСТЬ_ОЧЕРЕДИ],
    голова: usize,
    хвост: usize,
    shift_нажат: bool,
    ctrl_нажат: bool,
    alt_нажат: bool,
    /// Следующий байт — расширенный (после префикса 0xE0).
    расширенный: bool,
}

impl Состояние {
    const fn новое() -> Self {
        Self {
            очередь: [None; ЁМКОСТЬ_ОЧЕРЕДИ],
            голова: 0,
            хвост: 0,
            shift_нажат: false,
            ctrl_нажат: false,
            alt_нажат: false,
            расширенный: false,
        }
    }

    fn положить(&mut self, к: Клавиша) {
        let следующий = (self.хвост + 1) % ЁМКОСТЬ_ОЧЕРЕДИ;
        if следующий == self.голова {
            // Очередь полна — сбросим самый старый.
            self.голова = (self.голова + 1) % ЁМКОСТЬ_ОЧЕРЕДИ;
        }
        self.очередь[self.хвост] = Some(к);
        self.хвост = следующий;
    }

    fn взять(&mut self) -> Option<Клавиша> {
        if self.голова == self.хвост {
            return None;
        }
        let клавиша = self.очередь[self.голова].take();
        self.голова = (self.голова + 1) % ЁМКОСТЬ_ОЧЕРЕДИ;
        клавиша
    }
}

static СОСТОЯНИЕ: Mutex<Состояние> = Mutex::new(Состояние::новое());
static ПРИШЛА_F12: AtomicBool = AtomicBool::new(false);
static ПРИШЛА_PGUP: AtomicBool = AtomicBool::new(false);
static ПРИШЛА_PGDN: AtomicBool = AtomicBool::new(false);
static ПРИШЛА_ESC: AtomicBool = AtomicBool::new(false);

pub struct ДрайверКлавиатуры;

impl ДрайверКлавиатуры {
    pub const fn новый() -> Self {
        Self
    }
}

impl Драйвер for ДрайверКлавиатуры {
    fn имя(&self) -> &'static str {
        "клавиатура-ps2"
    }

    fn инициализировать(&mut self) -> Result<(), &'static str> {
        // Очищаем буфер вывода контроллера 0x60: иногда BIOS оставил байт.
        for _ in 0..16 {
            if (unsafe { вв8(0x64) } & 1) == 0 {
                break;
            }
            let _ = unsafe { вв8(0x60) };
        }
        Ok(())
    }

    fn обработать_прерывание(&mut self) {
        let байт = unsafe { вв8(0x60) };
        let mut с = СОСТОЯНИЕ.lock();

        if байт == 0xE0 {
            с.расширенный = true;
            return;
        }

        let нажата = (байт & 0x80) == 0;
        let код = байт & 0x7F;

        // Модификаторы.
        match (с.расширенный, код) {
            (false, 0x2A) | (false, 0x36) => {
                с.shift_нажат = нажата;
                с.расширенный = false;
                return;
            }
            (false, 0x1D) | (true, 0x1D) => {
                с.ctrl_нажат = нажата;
                с.расширенный = false;
                return;
            }
            (false, 0x38) | (true, 0x38) => {
                с.alt_нажат = нажата;
                с.расширенный = false;
                return;
            }
            _ => {}
        }

        if !нажата {
            // Отпускания клавиш — не интересуют (кроме модификаторов выше).
            с.расширенный = false;
            return;
        }

        let клавиша = if с.расширенный {
            match код {
                0x48 => Клавиша::Стрелка(Стрелка::Вверх),
                0x50 => Клавиша::Стрелка(Стрелка::Вниз),
                0x4B => Клавиша::Стрелка(Стрелка::Влево),
                0x4D => Клавиша::Стрелка(Стрелка::Вправо),
                0x49 => Клавиша::PgUp,
                0x51 => Клавиша::PgDn,
                0x47 => Клавиша::Home,
                0x4F => Клавиша::End,
                иной => Клавиша::Неизвестная(иной),
            }
        } else {
            расшифровать_простой_сканкод(код, с.shift_нажат)
        };

        с.расширенный = false;

        // Глобальные «горячие клавиши» — выставляем флаги, чтобы основной
        // цикл/отладочное окно мог среагировать.
        match клавиша {
            Клавиша::F(12) => ПРИШЛА_F12.store(true, Ordering::Relaxed),
            Клавиша::PgUp => ПРИШЛА_PGUP.store(true, Ordering::Relaxed),
            Клавиша::PgDn => ПРИШЛА_PGDN.store(true, Ordering::Relaxed),
            Клавиша::Esc => ПРИШЛА_ESC.store(true, Ordering::Relaxed),
            _ => {}
        }

        с.положить(клавиша);
    }

    fn завершить(&mut self) {}
}

fn расшифровать_простой_сканкод(код: u8, shift: bool) -> Клавиша {
    // Сканкоды Set 1 (стандарт IBM PC/AT, US-раскладка).
    let символ = match код {
        0x01 => return Клавиша::Esc,
        0x0E => return Клавиша::Возврат,
        0x0F => return Клавиша::ПробелТабуляции,
        0x1C => return Клавиша::Ввод,
        0x39 => ' ',
        0x3B => return Клавиша::F(1),
        0x3C => return Клавиша::F(2),
        0x3D => return Клавиша::F(3),
        0x3E => return Клавиша::F(4),
        0x3F => return Клавиша::F(5),
        0x40 => return Клавиша::F(6),
        0x41 => return Клавиша::F(7),
        0x42 => return Клавиша::F(8),
        0x43 => return Клавиша::F(9),
        0x44 => return Клавиша::F(10),
        0x57 => return Клавиша::F(11),
        0x58 => return Клавиша::F(12),
        // Цифры верхнего ряда.
        0x02 => if shift { '!' } else { '1' },
        0x03 => if shift { '@' } else { '2' },
        0x04 => if shift { '#' } else { '3' },
        0x05 => if shift { '$' } else { '4' },
        0x06 => if shift { '%' } else { '5' },
        0x07 => if shift { '^' } else { '6' },
        0x08 => if shift { '&' } else { '7' },
        0x09 => if shift { '*' } else { '8' },
        0x0A => if shift { '(' } else { '9' },
        0x0B => if shift { ')' } else { '0' },
        0x0C => if shift { '_' } else { '-' },
        0x0D => if shift { '+' } else { '=' },
        // QWERTY.
        0x10 => if shift { 'Q' } else { 'q' },
        0x11 => if shift { 'W' } else { 'w' },
        0x12 => if shift { 'E' } else { 'e' },
        0x13 => if shift { 'R' } else { 'r' },
        0x14 => if shift { 'T' } else { 't' },
        0x15 => if shift { 'Y' } else { 'y' },
        0x16 => if shift { 'U' } else { 'u' },
        0x17 => if shift { 'I' } else { 'i' },
        0x18 => if shift { 'O' } else { 'o' },
        0x19 => if shift { 'P' } else { 'p' },
        0x1A => if shift { '{' } else { '[' },
        0x1B => if shift { '}' } else { ']' },
        // ASDF.
        0x1E => if shift { 'A' } else { 'a' },
        0x1F => if shift { 'S' } else { 's' },
        0x20 => if shift { 'D' } else { 'd' },
        0x21 => if shift { 'F' } else { 'f' },
        0x22 => if shift { 'G' } else { 'g' },
        0x23 => if shift { 'H' } else { 'h' },
        0x24 => if shift { 'J' } else { 'j' },
        0x25 => if shift { 'K' } else { 'k' },
        0x26 => if shift { 'L' } else { 'l' },
        0x27 => if shift { ':' } else { ';' },
        0x28 => if shift { '"' } else { '\'' },
        0x29 => if shift { '~' } else { '`' },
        0x2B => if shift { '|' } else { '\\' },
        // ZXCV.
        0x2C => if shift { 'Z' } else { 'z' },
        0x2D => if shift { 'X' } else { 'x' },
        0x2E => if shift { 'C' } else { 'c' },
        0x2F => if shift { 'V' } else { 'v' },
        0x30 => if shift { 'B' } else { 'b' },
        0x31 => if shift { 'N' } else { 'n' },
        0x32 => if shift { 'M' } else { 'm' },
        0x33 => if shift { '<' } else { ',' },
        0x34 => if shift { '>' } else { '.' },
        0x35 => if shift { '?' } else { '/' },
        иной => return Клавиша::Неизвестная(иной),
    };
    Клавиша::Символ(символ)
}

#[inline]
unsafe fn вв8(порт: u16) -> u8 {
    let значение: u8;
    unsafe {
        core::arch::asm!(
            "in al, dx",
            out("al") значение,
            in("dx") порт,
            options(nomem, nostack, preserves_flags)
        );
    }
    значение
}

// === Публичный API для потребителей очереди и горячих клавиш. ===

pub fn взять_клавишу() -> Option<Клавиша> {
    СОСТОЯНИЕ.try_lock().and_then(|mut с| с.взять())
}

pub fn взять_флаг_f12() -> bool {
    ПРИШЛА_F12.swap(false, Ordering::Relaxed)
}

pub fn взять_флаг_pgup() -> bool {
    ПРИШЛА_PGUP.swap(false, Ordering::Relaxed)
}

pub fn взять_флаг_pgdn() -> bool {
    ПРИШЛА_PGDN.swap(false, Ordering::Relaxed)
}

pub fn взять_флаг_esc() -> bool {
    ПРИШЛА_ESC.swap(false, Ordering::Relaxed)
}
