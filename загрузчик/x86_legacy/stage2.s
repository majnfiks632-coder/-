; ДубинаОС — Stage2 загрузчика (без GRUB, без UEFI).
; Лицензия: GPL-3.0-or-later
;
; Stage2 загружается MBR по адресу 0x7E00 в режиме реальных адресов (16 бит).
;
; Этапы:
;   1. Включить линию A20 через быстрый порт 0x92.
;   2. Прочитать ядро ДубинаОС (плоский raw-бинарник, см. собрать_образ.sh)
;      в линейный адрес 0x10000 (под 1 МиБ — куда BIOS INT 13h может писать
;      без 32-битной адресации).
;   3. Загрузить минимальный GDT (нулевой + 32-битный код + данные).
;   4. Перейти в protected mode 32-бит.
;   5. Скопировать ядро из 0x10000 в 0x100000 (1 МиБ) через rep movsd.
;   6. Прыгнуть в 0x100000 с EAX = DUBINA_BOOT_MAGIC, EBX = указатель
;      на информационную структуру (пока 0).
;   7. Само ядро (kernel_entry, .code32) ставит свои таблицы страниц,
;      GDT для long mode и переключается в 64-битный режим. Stage2
;      намеренно не делает этого, чтобы не дублировать логику.

[BITS 16]
[ORG 0x7E00]

; -------------------------------------------------------------------
; Параметры компоновки.
; -------------------------------------------------------------------
%ifndef KERNEL_START_LBA
    %define KERNEL_START_LBA 64
%endif
%ifndef KERNEL_SECTORS
    %define KERNEL_SECTORS 256       ; 256 секторов = 128 КиБ по умолчанию
%endif

DUBINA_BOOT_MAGIC equ 0xD0BA0BE5
KERNEL_LOAD_BUF_SEG equ 0x1000      ; буфер чтения: seg=0x1000, off=0 → 0x10000
KERNEL_FINAL_LIN    equ 0x100000    ; куда ядро должно оказаться по итогу
CHUNK_SECTORS       equ 32           ; 32 сектора = 16 КиБ за один INT 13h

; -------------------------------------------------------------------
; Точка входа Stage2.
; -------------------------------------------------------------------
stage2_start:
    cli
    xor     ax, ax
    mov     ds, ax
    mov     es, ax
    mov     ss, ax
    mov     sp, 0x7C00

    ; ----- ОЧЕНЬ РАННИЙ МАЯК: пишем «STG2» прямо в VGA-буфер 0xB8000 -----
    ; Если этот маяк виден на экране в 4-й строке (160 байт = одна строка
    ; на стандартном текстовом VGA 80×25), значит Stage2 физически достиг
    ; своей точки входа. Пишем напрямую в видеопамять — без INT 0x10,
    ; без COM1 — на случай если BIOS-сервисы на этом железе сломаны.
    push    es
    mov     ax, 0xB800
    mov     es, ax
    mov     word [es:160*4 +  0], 0x4F53  ; 'S' белый на красном
    mov     word [es:160*4 +  2], 0x4F54  ; 'T'
    mov     word [es:160*4 +  4], 0x4F47  ; 'G'
    mov     word [es:160*4 +  6], 0x4F32  ; '2'
    mov     word [es:160*4 +  8], 0x4F2D  ; '-'
    mov     word [es:160*4 + 10], 0x4F52  ; 'R' (real mode)
    mov     word [es:160*4 + 12], 0x4F4D  ; 'M'
    pop     es

    sti

    mov     [boot_drive], dl

    call    init_serial

    mov     si, msg_hello
    call    print_string

    call    enable_a20
    mov     si, msg_a20
    call    print_string

    call    load_kernel
    jc      .read_failed

    mov     si, msg_kernel_loaded
    call    print_string

    ; Пробуем включить графический режим VBE 1024×768×32 (mode 0x118 у QEMU).
    ; Если получится — записываем информацию о framebuffer в boot_info; если нет —
    ; продолжаем в текстовом VGA-режиме (boot_info остаётся занулённым).
    call    try_enable_vbe

    cli
    lgdt    [gdt_descriptor]

    ; Switch to protected mode (PE=1)
    mov     eax, cr0
    or      eax, 1
    mov     cr0, eax

    jmp     CODE32_SEL:protected_mode_entry

.read_failed:
    mov     si, msg_kernel_error
    call    print_string
.halt:
    hlt
    jmp     .halt

; -------------------------------------------------------------------
; init_serial: настраивает COM1 на 38400 бод, 8N1 (для отладки).
; -------------------------------------------------------------------
init_serial:
    pusha
    mov     dx, 0x3F8 + 1
    mov     al, 0x00
    out     dx, al
    mov     dx, 0x3F8 + 3
    mov     al, 0x80
    out     dx, al
    mov     dx, 0x3F8 + 0
    mov     al, 0x03
    out     dx, al
    mov     dx, 0x3F8 + 1
    mov     al, 0x00
    out     dx, al
    mov     dx, 0x3F8 + 3
    mov     al, 0x03
    out     dx, al
    mov     dx, 0x3F8 + 2
    mov     al, 0xC7
    out     dx, al
    mov     dx, 0x3F8 + 4
    mov     al, 0x0B
    out     dx, al
    popa
    ret

; -------------------------------------------------------------------
; serial_putc: выводит AL в COM1.
; -------------------------------------------------------------------
serial_putc:
    push    ax
    push    dx
    mov     ah, al
.wait:
    mov     dx, 0x3F8 + 5
    in      al, dx
    test    al, 0x20
    jz      .wait
    mov     dx, 0x3F8
    mov     al, ah
    out     dx, al
    pop     dx
    pop     ax
    ret

; -------------------------------------------------------------------
; print_string: ASCII-строка → BIOS Teletype (VGA) + COM1 (serial).
; -------------------------------------------------------------------
print_string:
    pusha
.next:
    lodsb
    test    al, al
    jz      .done
    push    ax
    mov     ah, 0x0E
    mov     bx, 0x0007
    int     0x10
    pop     ax
    call    serial_putc
    jmp     .next
.done:
    popa
    ret

; -------------------------------------------------------------------
; enable_a20: быстрый способ через системный порт 0x92.
; -------------------------------------------------------------------
enable_a20:
    in      al, 0x92
    or      al, 0x02
    out     0x92, al
    ret

; -------------------------------------------------------------------
; load_kernel: читает KERNEL_SECTORS секторов начиная с LBA KERNEL_START_LBA
; в буфер по линейному адресу 0x10000 (seg=0x1000, off=0).
;
; Чтение порциями по CHUNK_SECTORS (64 сектора = 32 КиБ за вызов INT 13h),
; продвигая сегмент назначения.
; -------------------------------------------------------------------
load_kernel:
    pusha

    mov     dword [dap_lba_lo], KERNEL_START_LBA
    mov     dword [dap_lba_hi], 0
    mov     word  [dap_offset], 0
    mov     word  [dap_segment], KERNEL_LOAD_BUF_SEG

    mov     ecx, KERNEL_SECTORS
.loop:
    cmp     ecx, 0
    jle     .done

    ; chunk = min(ecx, CHUNK_SECTORS)
    mov     eax, ecx
    cmp     eax, CHUNK_SECTORS
    jbe     .have_chunk
    mov     eax, CHUNK_SECTORS
.have_chunk:
    mov     [dap_count], ax

    mov     si, dap
    mov     ah, 0x42
    mov     dl, [boot_drive]
    int     0x13
    jc      .err

    ; lba += chunk
    movzx   eax, word [dap_count]
    add     [dap_lba_lo], eax
    adc     dword [dap_lba_hi], 0

    ; segment += chunk * 32  (32 параграфа на сектор, 16 байт в параграфе)
    movzx   eax, word [dap_count]
    shl     ax, 5
    add     [dap_segment], ax

    ; ecx -= chunk
    movzx   eax, word [dap_count]
    sub     ecx, eax
    jmp     .loop

.done:
    popa
    clc
    ret

.err:
    popa
    stc
    ret

; -------------------------------------------------------------------
; try_enable_vbe: пробует включить VBE-режим 1024×768×32 (mode 0x118 у QEMU).
; Сохраняет адрес/шаг/размер framebuffer'а в boot_info.
; В случае неуспеха просто оставляет boot_info занулённым и продолжает
; в текстовом VGA-режиме.
;
; После успешного выполнения экран переключается в графический режим, и BIOS
; Teletype больше не работает — поэтому функцию вызываем последней перед PM.
; -------------------------------------------------------------------
try_enable_vbe:
    pusha
    push    es

    xor     ax, ax
    mov     es, ax

    ; Перебираем VBE-режимы по убыванию приоритета:
    ;   0x145 = 1024×768×32  (расширение Bochs)
    ;   0x144 =  800×600×32
    ;   0x143 =  640×480×32
    ; Для каждого: запросить mode_info → проверить bpp=32 + LFB → установить.
    mov     bx, 0x145
    call    .попробовать
    jc      .вышло
    mov     bx, 0x144
    call    .попробовать
    jc      .вышло
    mov     bx, 0x143
    call    .попробовать
    jc      .вышло
    ; Не нашли — текстовый режим.
    pop     es
    popa
    ret

.вышло:
    pop     es
    popa
    ret

; Внутренний помощник: BX = номер режима. CF=1 при успехе.
.попробовать:
    push    cx
    push    di
    push    bx
    mov     di, vbe_mode_info
    mov     cx, bx                  ; номер режима для 4F01h
    mov     ax, 0x4F01
    int     0x10
    cmp     ax, 0x004F
    jne     .нет

    ; Биты режима: 0 = supported, 7 = LFB.
    mov     ax, [vbe_mode_info + 0]
    and     ax, 0x0081
    cmp     ax, 0x0081
    jne     .нет

    ; bpp == 32 ?
    mov     al, [vbe_mode_info + 25]
    cmp     al, 32
    jne     .нет

    ; Заполнить boot_info.
    mov     eax, [vbe_mode_info + 40]
    mov     [boot_info + 4], eax
    mov     dword [boot_info + 8], 0
    movzx   eax, word [vbe_mode_info + 16]
    mov     [boot_info + 12], eax
    movzx   eax, word [vbe_mode_info + 18]
    mov     [boot_info + 16], eax
    movzx   eax, word [vbe_mode_info + 20]
    mov     [boot_info + 20], eax
    mov     al, [vbe_mode_info + 25]
    mov     [boot_info + 24], al
    mov     dword [boot_info + 0], 0x49424244

    ; Установить режим: BX = mode | 0x4000 (LFB).
    pop     bx
    push    bx
    or      bx, 0x4000
    mov     ax, 0x4F02
    int     0x10
    cmp     ax, 0x004F
    jne     .сброс_магии

    pop     bx
    pop     di
    pop     cx
    stc
    ret

.сброс_магии:
    mov     dword [boot_info + 0], 0
.нет:
    pop     bx
    pop     di
    pop     cx
    clc
    ret

; -------------------------------------------------------------------
; Минимальный GDT для protected mode 32-бит.
; Селекторы:
;   0x00 — null
;   0x08 — 32-битный код (CODE32_SEL): base=0, limit=4ГиБ
;   0x10 — 32-битные данные (DATA32_SEL): base=0, limit=4ГиБ
; -------------------------------------------------------------------
align 8
gdt_start:
    dq 0x0000000000000000
gdt_code32:
    dq 0x00CF9A000000FFFF
gdt_data32:
    dq 0x00CF92000000FFFF
gdt_end:

CODE32_SEL equ gdt_code32 - gdt_start
DATA32_SEL equ gdt_data32 - gdt_start

gdt_descriptor:
    dw gdt_end - gdt_start - 1
    dq gdt_start

; -------------------------------------------------------------------
; 32-битная часть Stage2.
; -------------------------------------------------------------------
[BITS 32]
protected_mode_entry:
    mov     ax, DATA32_SEL
    mov     ds, ax
    mov     es, ax
    mov     ss, ax
    mov     fs, ax
    mov     gs, ax
    mov     esp, 0x7C00

    ; ----- МАЯК PM32: пишем «PM32» в 5-ю строку VGA-буфера 0xB8000 -----
    ; Если этот маяк виден — значит Stage2 успешно перешёл в protected mode.
    ; VGA word: младший байт = ASCII, старший = атрибут (0x4F = белый/красный).
    mov     dword [0xB8000 + 160*5 +  0], 0x4F4D4F50   ; 'P' 'M'
    mov     dword [0xB8000 + 160*5 +  4], 0x4F324F33   ; '3' '2'

    ; Скопировать ядро из буфера 0x10000 в финальный 0x100000.
    ; Копируем KERNEL_SECTORS * 512 / 4 dword.
    mov     esi, 0x10000
    mov     edi, KERNEL_FINAL_LIN
    mov     ecx, (KERNEL_SECTORS * 512) / 4
    cld
    rep     movsd

    ; Передать управление ядру с magic в EAX, info в EBX.
    ; Если try_enable_vbe записал boot_info с магией "DBBI" — указываем на него,
    ; иначе передаём 0 и ядро использует текстовый режим VGA.
    mov     eax, DUBINA_BOOT_MAGIC
    mov     ebx, boot_info
    cmp     dword [boot_info], 0x49424244
    je      .have_boot_info
    mov     ebx, 0
.have_boot_info:
    jmp     CODE32_SEL:KERNEL_FINAL_LIN

; -------------------------------------------------------------------
; Данные.
; -------------------------------------------------------------------
[BITS 16]

boot_drive:        db 0

; Строки печатаются через BIOS Teletype (CP437) — кириллицы нет, используем
; транслитерацию русских слов латиницей.
msg_hello:         db "Stage2: privet, rezhim 16-bit", 13, 10, 0
msg_a20:           db "Stage2: liniya A20 vklyuchena", 13, 10, 0
msg_kernel_loaded: db "Stage2: yadro zagruzheno na 0x10000, perehod v PM32...", 13, 10, 0
msg_kernel_error:  db "Stage2: ne udalos' zagruzit yadro s diska", 13, 10, 0

align 4
dap:
    db      0x10                       ; size
    db      0x00                       ; reserved
dap_count:
    dw      0
dap_offset:
    dw      0
dap_segment:
    dw      0
dap_lba_lo:
    dd      0
dap_lba_hi:
    dd      0

; -------------------------------------------------------------------
; boot_info — структура, которую stage2 передаёт ядру в EBX.
; Layout (little-endian):
;   +0x00: magic   (4 байта) = 0x49424244 ('DBBI'), если valid
;   +0x04: fb_addr (8 байт)  — линейный адрес framebuffer
;   +0x0C: fb_pitch (4 байта)
;   +0x10: fb_width (4 байта)
;   +0x14: fb_height (4 байта)
;   +0x18: fb_bpp (1 байт)
;   +0x19: reserved (7 байт) — выравнивание
;   = 32 байта всего
;
; Если магия не выставлена — ядро игнорирует структуру и стартует в текстовом режиме.
; -------------------------------------------------------------------
align 4
boot_info:
    times 32 db 0

; VBE Mode Info Block (256 байт, см. VESA VBE 2.0 spec, § 4.1.4).
align 4
vbe_mode_info:
    times 256 db 0

align 512, db 0
