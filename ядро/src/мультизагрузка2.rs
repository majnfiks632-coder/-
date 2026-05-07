// ДубинаОС
// Авторские права (C) 2026, Команда ДубинаОС
// Лицензия: GPL-3.0-or-later
//
// Разбор информационной структуры Multiboot2.
//
// Спецификация: https://www.gnu.org/software/grub/manual/multiboot2/multiboot.html
//
// Информационная структура состоит из заголовка `общая_длина`/`зарезервировано`
// (по 4 байта), за которыми идут теги. Каждый тег выровнен на 8 байт и
// начинается с заголовка `тип`/`длина` (по 4 байта). Конечный тег имеет тип 0.
//
// Тег типа 6 — карта памяти; нас на этом этапе интересует только он.

#![allow(dead_code)]

use core::ptr;

/// Магия загрузчика Multiboot2 (передаётся в `EAX`).
pub const МАГИЯ: u64 = 0x36D7_6289;

/// Тип тега «конец списка».
const ТЕГ_КОНЕЦ: u32 = 0;
/// Тип тега «карта памяти».
const ТЕГ_КАРТА_ПАМЯТИ: u32 = 6;
/// Тип тега «framebuffer» (см. multiboot2 spec § 3.6.12).
const ТЕГ_FRAMEBUFFER: u32 = 8;

/// Тип буфера: 0 = индексированный (палитра), 1 = прямой RGB, 2 = текстовый.
pub const ФБ_ИНДЕКСИРОВАННЫЙ: u8 = 0;
pub const ФБ_RGB: u8 = 1;
pub const ФБ_ТЕКСТОВЫЙ: u8 = 2;

/// Сведения о framebuffer от загрузчика.
#[derive(Debug, Clone, Copy)]
pub struct СведенияFramebuffer {
    pub адрес: u64,
    pub шаг: u32,
    pub ширина: u32,
    pub высота: u32,
    pub бит_на_пиксель: u8,
    pub тип_буфера: u8,
    /// Поля только для типа 1 (RGB): позиция и размер каждого цвета в пикселе.
    pub позиция_красного: u8,
    pub размер_красного: u8,
    pub позиция_зелёного: u8,
    pub размер_зелёного: u8,
    pub позиция_синего: u8,
    pub размер_синего: u8,
}

/// Тип записи карты памяти: «доступная для использования».
pub const ОБЛАСТЬ_ДОСТУПНА: u32 = 1;
/// Тип записи карты памяти: «зарезервировано BIOS».
pub const ОБЛАСТЬ_ЗАРЕЗЕРВИРОВАНА: u32 = 2;
/// Тип записи карты памяти: «ACPI восстанавливаемая».
pub const ОБЛАСТЬ_ACPI_ВОССТАНОВИМАЯ: u32 = 3;
/// Тип записи карты памяти: «ACPI NVS».
pub const ОБЛАСТЬ_ACPI_NVS: u32 = 4;
/// Тип записи карты памяти: «плохая память».
pub const ОБЛАСТЬ_ПЛОХАЯ: u32 = 5;

/// Одна запись карты памяти.
#[derive(Debug, Clone, Copy)]
pub struct ОбластьПамяти {
    pub база: u64,
    pub длина: u64,
    pub тип_области: u32,
}

/// Информационная структура Multiboot2 — высокоуровневая обёртка.
pub struct ИнфоMultiboot2 {
    указатель: *const u8,
    общая_длина: u32,
}

impl ИнфоMultiboot2 {
    /// # Безопасность
    /// Указатель должен быть валидным указателем на структуру Multiboot2.
    pub unsafe fn новая(указатель: u64) -> Result<Self, &'static str> {
        if указатель == 0 {
            return Err("указатель Multiboot2 равен нулю");
        }
        if указатель & 0b111 != 0 {
            return Err("указатель Multiboot2 не выровнен на 8 байт");
        }
        let указатель = указатель as *const u8;
        let общая_длина = unsafe { ptr::read_unaligned(указатель as *const u32) };
        if общая_длина < 8 {
            return Err("слишком короткая структура Multiboot2");
        }
        Ok(Self {
            указатель,
            общая_длина,
        })
    }

    /// Найти тег framebuffer и вернуть его содержимое.
    /// Тело тега (см. multiboot2 spec):
    ///   u64 framebuffer_addr
    ///   u32 framebuffer_pitch
    ///   u32 framebuffer_width
    ///   u32 framebuffer_height
    ///   u8  framebuffer_bpp
    ///   u8  framebuffer_type
    ///   u8  reserved
    ///   ... (поля цветов для type=1: red_pos, red_size, green_pos, green_size, blue_pos, blue_size)
    pub fn framebuffer(&self) -> Option<СведенияFramebuffer> {
        let mut итератор = ИтераторТегов::новый(self);
        loop {
            let тег = итератор.следующий()?;
            if тег.тип_тега != ТЕГ_FRAMEBUFFER || тег.длина < 32 {
                continue;
            }
            // Тело начинается через 8 байт (u32 type + u32 length).
            let тело = unsafe { тег.указатель.add(8) };
            let адрес = unsafe { ptr::read_unaligned(тело as *const u64) };
            let шаг = unsafe { ptr::read_unaligned(тело.add(8) as *const u32) };
            let ширина = unsafe { ptr::read_unaligned(тело.add(12) as *const u32) };
            let высота = unsafe { ptr::read_unaligned(тело.add(16) as *const u32) };
            let бит_на_пиксель = unsafe { ptr::read_unaligned(тело.add(20) as *const u8) };
            let тип_буфера = unsafe { ptr::read_unaligned(тело.add(21) as *const u8) };
            // тело.add(22..24) — резерв.
            let (rp, rs, gp, gs, bp, bs) = if тип_буфера == ФБ_RGB && тег.длина >= 38 {
                unsafe {
                    (
                        ptr::read_unaligned(тело.add(24) as *const u8),
                        ptr::read_unaligned(тело.add(25) as *const u8),
                        ptr::read_unaligned(тело.add(26) as *const u8),
                        ptr::read_unaligned(тело.add(27) as *const u8),
                        ptr::read_unaligned(тело.add(28) as *const u8),
                        ptr::read_unaligned(тело.add(29) as *const u8),
                    )
                }
            } else {
                (0, 0, 0, 0, 0, 0)
            };
            return Some(СведенияFramebuffer {
                адрес,
                шаг,
                ширина,
                высота,
                бит_на_пиксель,
                тип_буфера,
                позиция_красного: rp,
                размер_красного: rs,
                позиция_зелёного: gp,
                размер_зелёного: gs,
                позиция_синего: bp,
                размер_синего: bs,
            });
        }
    }

    /// Итератор по записям карты памяти. Если карты памяти нет — итератор пуст.
    pub fn карта_памяти(&self) -> ИтераторКартыПамяти {
        let mut итератор = ИтераторТегов::новый(self);
        // Ищем тег карты памяти.
        loop {
            match итератор.следующий() {
                None => {
                    return ИтераторКартыПамяти {
                        указатель: core::ptr::null(),
                        конец: core::ptr::null(),
                        размер_записи: 0,
                    };
                }
                Some(тег) => {
                    if тег.тип_тега == ТЕГ_КАРТА_ПАМЯТИ && тег.длина >= 16 {
                        // Тело тега: u32 размер_записи, u32 версия_записи, потом записи.
                        let данные = unsafe { тег.указатель.add(8) };
                        let размер_записи =
                            unsafe { ptr::read_unaligned(данные as *const u32) } as usize;
                        let _версия =
                            unsafe { ptr::read_unaligned(данные.add(4) as *const u32) };
                        let начало = unsafe { данные.add(8) };
                        let конец = unsafe { тег.указатель.add(тег.длина as usize) };
                        return ИтераторКартыПамяти {
                            указатель: начало,
                            конец,
                            размер_записи,
                        };
                    }
                }
            }
        }
    }
}

/// Внутренний итератор по сырым тегам.
struct ИтераторТегов<'а> {
    указатель: *const u8,
    конец: *const u8,
    _жизнь: core::marker::PhantomData<&'а u8>,
}

struct СырогоТег {
    тип_тега: u32,
    длина: u32,
    указатель: *const u8,
}

impl<'а> ИтераторТегов<'а> {
    fn новый(инфо: &'а ИнфоMultiboot2) -> Self {
        let начало = unsafe { инфо.указатель.add(8) };
        let конец = unsafe { инфо.указатель.add(инфо.общая_длина as usize) };
        Self {
            указатель: начало,
            конец,
            _жизнь: core::marker::PhantomData,
        }
    }

    fn следующий(&mut self) -> Option<СырогоТег> {
        if self.указатель >= self.конец {
            return None;
        }
        let тип_тега = unsafe { ptr::read_unaligned(self.указатель as *const u32) };
        let длина = unsafe { ptr::read_unaligned(self.указатель.add(4) as *const u32) };
        if тип_тега == ТЕГ_КОНЕЦ {
            return None;
        }
        if длина < 8 {
            return None;
        }
        let тег = СырогоТег {
            тип_тега,
            длина,
            указатель: self.указатель,
        };
        // Перейти к следующему тегу с выравниванием по 8 байт.
        let следующий_сырой = unsafe { self.указатель.add(длина as usize) };
        let выровненный = ((следующий_сырой as usize + 7) & !7usize) as *const u8;
        self.указатель = выровненный;
        Some(тег)
    }
}

/// Итератор по записям карты памяти.
pub struct ИтераторКартыПамяти {
    указатель: *const u8,
    конец: *const u8,
    размер_записи: usize,
}

impl Iterator for ИтераторКартыПамяти {
    type Item = ОбластьПамяти;
    fn next(&mut self) -> Option<Self::Item> {
        if self.размер_записи == 0 {
            return None;
        }
        if self.указатель >= self.конец {
            return None;
        }
        let база = unsafe { ptr::read_unaligned(self.указатель as *const u64) };
        let длина = unsafe { ptr::read_unaligned(self.указатель.add(8) as *const u64) };
        let тип_области = unsafe { ptr::read_unaligned(self.указатель.add(16) as *const u32) };
        let _зарезервировано =
            unsafe { ptr::read_unaligned(self.указатель.add(20) as *const u32) };
        self.указатель = unsafe { self.указатель.add(self.размер_записи) };
        Some(ОбластьПамяти {
            база,
            длина,
            тип_области,
        })
    }
}
