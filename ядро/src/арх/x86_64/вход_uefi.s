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

    # ---------- Маяк в GOP framebuffer ----------
    # ВАЖНО: на реальном UEFI-железе (например ASUS X552EA, AMI 2013 г.)
    # легаси-VGA-буфер 0xB8000 НЕ работает после ExitBootServices —
    # запись туда даёт #GP, у нас нет IDT → triple fault → ребут.
    # В QEMU+OVMF VGA эмулируется и записи проходят, поэтому в QEMU
    # это работало — мы зря на это полагались.
    #
    # Поэтому НИКАКИХ записей в 0xB8000 в этом файле нет. Используем
    # только GOP framebuffer-адрес, который дал загрузчик через
    # ИнфоЗагрузки[24]. Этот адрес — валидный физический MMIO,
    # покрытый идентичным маппингом UEFI и нашими собственными PD-таблицами.
    #
    # Рисуем большой ярко-жёлтый прямоугольник в верхнем-левом углу:
    # 4 строки × 512 пикселей. Если на экране после ASUS-логотипа
    # появилось такое жёлтое пятно — значит kernel_entry_uefi достигнут,
    # ИнфоЗагрузки валиден, framebuffer-адрес корректный.
    test    rsi, rsi
    jz      skip_fb_marker
    mov     rax, [rsi + 24]               # фб_адрес
    test    rax, rax
    jz      skip_fb_marker
    # Берём фб_ширина из ИнфоЗагрузки[40] (в пикселях) — для размера маркера
    # этого хватит. Шаг (фб_шаг_пикселей) лежит по +48, но обычно совпадает
    # с шириной в типичных режимах (1024×768, 1366×768, 1920×1080).
    mov     edx, dword ptr [rsi + 48]     # фб_шаг_пикселей
    test    edx, edx
    jz      skip_fb_marker
    # 4 строки
    mov     r10, 4
fb_row_loop:
    mov     rdi, rax                       # старт строки
    mov     rcx, 512                       # 512 пикселей в строку
    mov     ebx, 0x00FFFF00               # ярко-жёлтый (BGRX и RGBX оба ок)
fb_pix_loop:
    mov     dword ptr [rdi], ebx
    add     rdi, 4
    dec     rcx
    jnz     fb_pix_loop
    # rax += шаг * 4 байта
    mov     ecx, edx
    shl     rcx, 2
    add     rax, rcx
    dec     r10
    jnz     fb_row_loop
skip_fb_marker:

    # ---------- Сохраняем fb_адрес/fb_шаг для раннего IDT ----------
    # Если ниже что-то фолтит (триплфолт = ребут на голом железе),
    # ранний обработчик нарисует широкую красную полосу в FB и зависнет.
    # Так мы УВИДИМ фолт вместо немой перезагрузки.
    test    rsi, rsi
    jz      skip_save_fb
    mov     rax, [rsi + 24]                 # фб_адрес
    lea     rdi, [rip + ranniy_fb_address]
    mov     [rdi], rax
    mov     eax, dword ptr [rsi + 48]       # фб_шаг_пикселей
    lea     rdi, [rip + ranniy_fb_stride]
    mov     dword ptr [rdi], eax
skip_save_fb:

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

    # ---------- Ставим минимальный ранний IDT ----------
    # Назначение: перехватить любую CPU-исключение (#GP, #PF, #UD, #DE и т.д.)
    # и вместо безмолвного triple-fault'а нарисовать видимую полосу в FB
    # и повиснуть. На железе это превращает «ребут на ASUS-логотипе» в
    # «зависший экран с белой полосой» — пользователь видит что упало.
    call    install_early_idt

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

# ============================================================
# Минимальный ранний IDT с одним общим обработчиком фолтов.
#
# Каждая из 32 записей CPU-исключений ведёт в common_fault_handler.
# Обработчик пишет в FB widely-видимую полосу (тёмно-красная —
# 0x000000FF в BGRA, при любом RGB/BGR порядке выглядит как красный
# либо синий) и зависает. Главное — не происходит безмолвного
# triple-fault → reset.
# ============================================================
.section .text, "ax"
.code64
.global install_early_idt
.type install_early_idt, @function
install_early_idt:
    # Заполняем 32 IDT-входа адресом common_fault_handler.
    # Формат IDT-входа (16 байт):
    #   +0  смещение[0..15]
    #   +2  селектор (CS = 0x08)
    #   +4  ist=0
    #   +5  type/attr = 0x8E (P=1, DPL=0, type=interrupt gate)
    #   +6  смещение[16..31]
    #   +8  смещение[32..63]
    #   +12 резерв = 0
    lea     rax, [rip + common_fault_handler]
    lea     rdi, [rip + ranniy_idt_data]
    mov     rcx, 32
fill_idt_entry:
    mov     word ptr  [rdi + 0],  ax              # смещение 0..15
    mov     word ptr  [rdi + 2],  0x08            # селектор CS
    mov     byte ptr  [rdi + 4],  0               # ist = 0
    mov     byte ptr  [rdi + 5],  0x8E            # P=1, DPL=0, interrupt gate
    mov     rbx, rax
    shr     rbx, 16
    mov     word ptr  [rdi + 6],  bx              # смещение 16..31
    mov     rbx, rax
    shr     rbx, 32
    mov     dword ptr [rdi + 8],  ebx             # смещение 32..63
    mov     dword ptr [rdi + 12], 0               # резерв
    add     rdi, 16
    dec     rcx
    jnz     fill_idt_entry

    # Сборка IDTR-указателя.
    lea     rax, [rip + ranniy_idt_pointer]
    mov     word ptr  [rax + 0], 32 * 16 - 1      # лимит
    lea     rbx, [rip + ranniy_idt_data]
    mov     qword ptr [rax + 2], rbx
    lidt    [rax]
    ret
.size install_early_idt, . - install_early_idt

.global common_fault_handler
.type common_fault_handler, @function
common_fault_handler:
    cli
    # Рисуем 16 строк ярко-красным (0x00FF0000) на всю ширину FB.
    # Это безусловный сигнал «ядро поймало исключение и повисло».
    lea     rax, [rip + ranniy_fb_address]
    mov     rax, [rax]
    test    rax, rax
    jz      fault_hang
    lea     rdx, [rip + ranniy_fb_stride]
    mov     edx, dword ptr [rdx]
    test    edx, edx
    jz      fault_hang
    mov     r10, 16
fault_fill_row:
    mov     rdi, rax
    mov     ecx, edx                              # пикселей в строке
    mov     ebx, 0x00FF0000                       # ярко-красный
fault_fill_pix:
    mov     dword ptr [rdi], ebx
    add     rdi, 4
    dec     rcx
    jnz     fault_fill_pix
    mov     ebx, edx
    shl     rbx, 2                                 # шаг_байт = шаг_пикс * 4
    add     rax, rbx
    dec     r10
    jnz     fault_fill_row
fault_hang:
    cli
    hlt
    jmp     fault_hang
.size common_fault_handler, . - common_fault_handler

# Данные раннего IDT — 32 * 16 = 512 байт + 10 байт указателя + 16 байт fb.
# Лежат в .bss, занулены загрузчиком на старте.
.section .bss
.align 16
.global ranniy_idt_data
ranniy_idt_data:
    .skip 32 * 16
.global ranniy_idt_pointer
ranniy_idt_pointer:
    .skip 10
.global ranniy_fb_address
ranniy_fb_address:
    .skip 8
.global ranniy_fb_stride
ranniy_fb_stride:
    .skip 4
    .skip 4    # выравнивание
