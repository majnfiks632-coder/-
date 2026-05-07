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

    # ---------- Загружаем наш GDT ----------
    lea     rax, [rip + gdt_pointer]
    lgdt    [rax]

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
