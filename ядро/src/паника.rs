// ДубинаОС
// Авторские права (C) 2026, Команда ДубинаОС
// Лицензия: GPL-3.0-or-later
//
// Обработчик паники ядра.

use core::panic::PanicInfo;

#[panic_handler]
fn паника(инфо: &PanicInfo) -> ! {
    // Печатаем максимум диагностики и зависаем.
    crate::println!();
    crate::println!("======== ПАНИКА ЯДРА ========");
    if let Some(место) = инфо.location() {
        crate::println!("Место: {}:{}:{}", место.file(), место.line(), место.column());
    }
    crate::println!("Сообщение: {}", инфо.message());
    crate::println!("=============================");

    crate::арх::халт_навсегда();
}
