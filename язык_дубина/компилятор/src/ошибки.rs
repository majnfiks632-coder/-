// ДубинаОС
// Авторские права (C) 2026, Команда ДубинаОС
// Лицензия: GPL-3.0-or-later
//
// Типы ошибок компилятора.

use std::fmt;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Положение {
    pub строка: u32,
    pub столбец: u32,
}

impl fmt::Display for Положение {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}:{}", self.строка, self.столбец)
    }
}

#[derive(Debug, Clone)]
pub struct Ошибка {
    pub положение: Положение,
    pub сообщение: String,
}

impl fmt::Display for Ошибка {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "[{}] {}", self.положение, self.сообщение)
    }
}

impl Ошибка {
    pub fn новая(положение: Положение, сообщение: impl Into<String>) -> Self {
        Self { положение, сообщение: сообщение.into() }
    }
}
