/*
 * Точка входа ядра ДубинаОС, вызываемая из 64-битного контекста (UEFI).
 *
 * UEFI-загрузчик уже:
 *   - переключил CPU в long mode и включил paging (identity-map от UEFI),
 *   - подготовил собственный стек и GDT.
 *
 * Этот стаб:
 *   1. Запрещает прерывания.
 *   2. Поднимает наши собственные таблицы страниц (PML4/PDPT/PD_0..3),
 *      идентичные тем, что строит 32-битный путь, чтобы дальше всё ядро
 *      не зависело от того, как именно UEFI отображал память.
 *   3. Загружает наш GDT и переключается на наш 64-битный CS.
 *   4. Перезаряжает стек на `stack_top` (BSS, 64 КиБ).
 *   5. Вызывает Rust-функцию `_yadro_start(rdi=магия, rsi=инфо)`.
 *
 * Символы PML4/PDPT/PD_0..3, stack_top и gdt_pointer определены в `вход.s`.
 * Этот файл подключается отдельной директивой global_asm! и линкуется
 * вместе с ядром.
 */

# ------------------------------------------------------------
# Точка входа из UEFI (64-битная).
# UEFI-загрузчик передаёт:
#   RDI = магия (МАГИЯ_ДУБ_ПРОТО, см. инфо_загрузки.rs)
#   RSI = указатель на структуру ИнфоЗагрузки
# ------------------------------------------------------------
.section .text.kernel_entry_uefi, "ax"
.code64
.global kernel_entry_uefi
.type kernel_entry_uefi, @function
kernel_entry_uefi:
    cli
    cld

    # Сохраняем переданные аргументы — они нам понадобятся после переключения
    # таблиц страниц и GDT.
    mov     r12, rdi
    mov     r13, rsi

    # ---------- ОЧЕНЬ РАННИЙ МАЯК (до любого CR3/GDT/стека) ----------
    # Если эту строку видно в правом верхнем углу VGA текстового режима
    # (или в углу framebuffer'а как ярко-белые квадраты), значит ядро
    # физически достигло своей точки входа из UEFI. Без этого маяка
    # неясно, упал ли загрузчик в ExitBootServices или ядро в первой же
    # инструкции после прыжка.
    #
    # Пишем «UEFI->K» в правый край VGA-буфера 0xB8000 (32-я колонка).
    # Это идентичный маппинг от UEFI остаётся валидным до mov cr3.
    mov     rax, 0xB8000
    mov     word ptr [rax + 64],  0x4F55   # 'U' красный фон, белый текст
    mov     word ptr [rax + 66],  0x4F45   # 'E'
    mov     word ptr [rax + 68],  0x4F46   # 'F'
    mov     word ptr [rax + 70],  0x4F49   # 'I'
    mov     word ptr [rax + 72],  0x4F2D   # '-'
    mov     word ptr [rax + 74],  0x4F3E   # '>'
    mov     word ptr [rax + 76],  0x4F4B   # 'K'
    mov     word ptr [rax + 78],  0x4F31   # '1'

    # ---------- Маяк в GOP framebuffer ----------
    # На реальном UEFI-железе VGA-буфер 0xB8000 не работает (нет legacy VGA).
    # Зато framebuffer от GOP должен быть валиден: загрузчик дал нам указатель
    # на ИнфоЗагрузки в RSI, в нём фб_адрес лежит по смещению 24.
    #
    # Идентичный маппинг от UEFI ещё активен (мы не трогали CR3), так что
    # framebuffer точно достижим через свой физический адрес.
    #
    # Рисуем горизонтальную красно-синюю полосу из 256 пикселей по адресу
    # фб_адрес+0. Это полоса в верхнем-левом углу экрана.
    test    rsi, rsi
    jz      skip_fb_marker
    mov     rax, [rsi + 24]               # фб_адрес
    test    rax, rax
    jz      skip_fb_marker
    mov     rcx, 512                       # 512 4-байтных пикселей
    mov     ebx, 0x00FFFF00               # ярко-жёлтый (BGRX/RGBX — оба варианта)
fb_marker_loop:
    mov     dword ptr [rax], ebx
    add     rax, 4
    dec     rcx
    jnz     fb_marker_loop
skip_fb_marker:

    # ---------- Подготовка таблиц страниц ----------
    # PML4[0] -> PDPT
    lea     rax, [rip + PML4]
    lea     rcx, [rip + PDPT]
    or      rcx, 0b11
    mov     [rax], rcx

    # PDPT[0..3] -> PD_0..PD_3
    lea     rax, [rip + PDPT]
    lea     rcx, [rip + PD_0]
    or      rcx, 0b11
    mov     [rax + 0*8], rcx
    lea     rcx, [rip + PD_1]
    or      rcx, 0b11
    mov     [rax + 1*8], rcx
    lea     rcx, [rip + PD_2]
    or      rcx, 0b11
    mov     [rax + 2*8], rcx
    lea     rcx, [rip + PD_3]
    or      rcx, 0b11
    mov     [rax + 3*8], rcx

    # Заполняем PD_0..PD_3 — 2048 записей по 2 МиБ, итого 4 ГиБ identity-map.
    lea     rdi, [rip + PD_0]
    xor     rsi, rsi
    mov     rcx, 2048
fill_uefi_loop:
    mov     rax, rsi
    or      rax, 0b10000011        # Present | Writable | PageSize (2 МиБ)
    mov     [rdi], rax
    add     rdi, 8
    add     rsi, 0x200000
    dec     rcx
    jnz     fill_uefi_loop

    # ---------- Переключаем CR3 на нашу PML4 ----------
    lea     rax, [rip + PML4]
    mov     cr3, rax

    # Маяк после CR3-переключения: «K2» в той же строке.
    mov     rax, 0xB8000
    mov     word ptr [rax + 80],  0x4F4B   # 'K'
    mov     word ptr [rax + 82],  0x4F32   # '2'

    # ---------- Загружаем наш GDT ----------
    lea     rax, [rip + gdt_pointer]
    lgdt    [rax]

    # Маяк после LGDT.
    mov     rax, 0xB8000
    mov     word ptr [rax + 84],  0x4F4B   # 'K'
    mov     word ptr [rax + 86],  0x4F33   # '3'

    # Перезагружаем CS через far ret.
    # rax = адрес метки 1, rcx = селектор кода.
    lea     rax, [rip + 1f]
    mov     rcx, 0x08
    push    rcx
    push    rax
    retfq
1:
    # Перезагружаем сегментные регистры данных.
    mov     ax, 0x10
    mov     ds, ax
    mov     es, ax
    mov     ss, ax
    mov     fs, ax
    mov     gs, ax

    # Свежий стек
    lea     rsp, [rip + stack_top]
    mov     rbp, rsp
    cld

    # Маяк после far-ret + загрузки сегментов + смены стека.
    mov     rax, 0xB8000
    mov     word ptr [rax + 88],  0x4F4B   # 'K'
    mov     word ptr [rax + 90],  0x4F34   # '4'

    # Восстанавливаем аргументы.
    mov     rdi, r12
    mov     rsi, r13

    call    _yadro_start

    # Если вернулся (что не должно случиться) — зависаем.
hang_uefi:
    cli
    hlt
    jmp     hang_uefi

.size kernel_entry_uefi, . - kernel_entry_uefi
