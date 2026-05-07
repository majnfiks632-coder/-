/*
 * Точка входа ядра ДубинаОС для архитектуры x86_64.
 *
 * Файл подключается через global_asm! из src/арх/x86_64/mod.rs.
 * Содержит:
 *   - Заголовок Multiboot2 (для совместимости с GRUB и `qemu -kernel`)
 *   - 32-битную точку входа _начало
 *   - Установку начальной таблицы страниц (отображение 0..4 ГиБ страницами по 2 МиБ)
 *   - Переход в long mode (64 бита)
 *   - Вызов функции на Rust: _yadro_start(magic: u64, info: u64)
 *
 * Все метки внутри .s файла на ASCII — этого требует ассемблер LLVM.
 * Сама ОС, файлы исходников Rust, имена переменных и т.д. остаются на русском.
 */

# ------------------------------------------------------------
# Заголовок Multiboot2
# ------------------------------------------------------------
.section .multiboot_header, "a"
.align 8
mb_hdr_start:
    .long 0xE85250D6                                    # магия Multiboot2
    .long 0                                              # архитектура: i386 protected mode
    .long mb_hdr_end - mb_hdr_start                      # длина заголовка
    .long -(0xE85250D6 + 0 + (mb_hdr_end - mb_hdr_start)) # контрольная сумма

    # Тэг framebuffer (тип 5): просим у GRUB графический режим 1024×768×32.
    # Длина тэга = 20 байт (5 полей по 4 байта). Если GRUB не сможет — упадёт
    # на текстовый режим, что нас тоже устраивает (поле bpp = 0).
    .align 8
    .short 5            # тип: framebuffer
    .short 0            # флаги: 0 = обязательно для нас не помечаем,
                        # GRUB всё равно постарается выдать.
    .long 20            # длина тэга
    .long 1024          # желаемая ширина
    .long 768           # желаемая высота
    .long 32            # желаемая глубина (бит на пиксель)

    # Завершающий тэг
    .align 8
    .short 0
    .short 0
    .long 8
mb_hdr_end:

# ------------------------------------------------------------
# BSS: стек + начальные таблицы страниц
# ------------------------------------------------------------
.section .bss
.align 16
stack_bottom:
    .skip 65536                # 64 КБ начального стека
.global stack_top
stack_top:

.align 4096
PML4: .skip 4096
PDPT: .skip 4096
PD_0: .skip 4096               # отображение 0..1 ГиБ
PD_1: .skip 4096               # отображение 1..2 ГиБ
PD_2: .skip 4096               # отображение 2..3 ГиБ
PD_3: .skip 4096               # отображение 3..4 ГиБ

# ------------------------------------------------------------
# Точка входа (32-битная, согласно Multiboot2 / нашему загрузчику Stage2)
# ------------------------------------------------------------
.section .text.kernel_entry, "ax"
.code32
.global kernel_entry
.type kernel_entry, @function
kernel_entry:
    cli
    cld

    # Устанавливаем стек
    mov esp, offset stack_top
    mov ebp, esp

    # Сохраняем переданные загрузчиком регистры (для long mode потом)
    mov edi, eax                  # магическое число от загрузчика
    mov esi, ebx                  # указатель на информационную структуру

    # ---------- Подготовка таблиц страниц ----------
    # PML4[0] -> PDPT
    lea eax, [PDPT]
    or  eax, 0b11                 # Present | Writable
    mov [PML4], eax
    mov dword ptr [PML4 + 4], 0

    # PDPT[0..3] -> PD_0..PD_3
    lea eax, [PD_0]
    or  eax, 0b11
    mov [PDPT + 0*8], eax
    mov dword ptr [PDPT + 0*8 + 4], 0
    lea eax, [PD_1]
    or  eax, 0b11
    mov [PDPT + 1*8], eax
    mov dword ptr [PDPT + 1*8 + 4], 0
    lea eax, [PD_2]
    or  eax, 0b11
    mov [PDPT + 2*8], eax
    mov dword ptr [PDPT + 2*8 + 4], 0
    lea eax, [PD_3]
    or  eax, 0b11
    mov [PDPT + 3*8], eax
    mov dword ptr [PDPT + 3*8 + 4], 0

    # Заполняем PD_0..PD_3 — 2048 записей по 2 МиБ, итого 4 ГиБ identity-map.
    # Все четыре PD идут подряд в памяти, поэтому индексируем от PD_0.
    xor ecx, ecx                  # индекс страницы
    xor ebx, ebx                  # текущий физический адрес (low)
fill_loop:
    mov eax, ebx
    or  eax, 0b10000011           # Present | Writable | PageSize (2 МиБ)
    lea edx, [PD_0]
    mov [edx + ecx*8], eax
    mov dword ptr [edx + ecx*8 + 4], 0
    add ebx, 0x200000             # +2 МиБ
    inc ecx
    cmp ecx, 2048                 # 4 * 512
    jl  fill_loop

    # Загружаем PML4 в CR3
    lea eax, [PML4]
    mov cr3, eax

    # Включаем PAE (CR4.PAE = бит 5)
    mov eax, cr4
    or  eax, 1 << 5
    mov cr4, eax

    # Устанавливаем EFER.LME (бит 8) и EFER.NXE (бит 11) — последнее необходимо,
    # чтобы в записях таблиц страниц можно было использовать бит NX (запрет исполнения).
    # MSR EFER = 0xC0000080.
    mov ecx, 0xC0000080
    rdmsr
    or  eax, (1 << 8) | (1 << 11)
    wrmsr

    # Включаем страничную трансляцию + WP (CR0.PG | CR0.WP)
    mov eax, cr0
    or  eax, (1 << 31) | (1 << 16)
    mov cr0, eax

    # Загружаем 64-битный GDT
    lgdt [gdt_pointer]

    # Дальний переход в 64-битный сегмент кода. Поскольку LLVM IA в Intel-режиме
    # не принимает синтаксис `jmp seg:offset`, кодируем команду вручную:
    # JMP ptr16:32 = 0xEA + 32-битное смещение + 16-битный селектор = 7 байт.
    .byte 0xEA
    .long long_mode_start
    .short 0x08

.code64
long_mode_start:
    # Перезагружаем сегментные регистры данных
    mov ax, 0x10
    mov ds, ax
    mov es, ax
    mov ss, ax
    mov fs, ax
    mov gs, ax

    # Восстанавливаем стек, выравнивание и параметры вызова
    lea rsp, [stack_top]
    mov rbp, rsp
    cld

    # Аргументы: rdi = магия, rsi = инфо (32-битные edi/esi автоматически
    # обнуляют верхнюю половину при загрузке выше).
    call _yadro_start

    # Если вернулся (что не должно случиться) — зависаем
hang:
    cli
    hlt
    jmp hang

.size kernel_entry, . - kernel_entry

# ------------------------------------------------------------
# GDT для long mode
# ------------------------------------------------------------
.section .rodata
.align 8
gdt_start:
    .quad 0                                          # 0x00: NULL
gdt_code64:
    .quad 0x00AF9A000000FFFF                         # 0x08: 64-битный код, DPL=0
gdt_data:
    .quad 0x00CF92000000FFFF                         # 0x10: данные, DPL=0
gdt_end:

.global gdt_pointer
gdt_pointer:
    .short gdt_end - gdt_start - 1
    .quad  gdt_start
