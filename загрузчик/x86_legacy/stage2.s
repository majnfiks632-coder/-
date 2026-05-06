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

    ; Скопировать ядро из буфера 0x10000 в финальный 0x100000.
    ; Копируем KERNEL_SECTORS * 512 / 4 dword.
    mov     esi, 0x10000
    mov     edi, KERNEL_FINAL_LIN
    mov     ecx, (KERNEL_SECTORS * 512) / 4
    cld
    rep     movsd

    ; Передать управление ядру с magic в EAX, info в EBX.
    mov     eax, DUBINA_BOOT_MAGIC
    mov     ebx, 0
    jmp     CODE32_SEL:KERNEL_FINAL_LIN

; -------------------------------------------------------------------
; Данные.
; -------------------------------------------------------------------
[BITS 16]

boot_drive:        db 0

msg_hello:         db "Stage2: hello, real mode 16bit", 13, 10, 0
msg_a20:           db "Stage2: A20 enabled", 13, 10, 0
msg_kernel_loaded: db "Stage2: kernel loaded at 0x10000, switching to PM32...", 13, 10, 0
msg_kernel_error:  db "Stage2: failed to load kernel from disk", 13, 10, 0

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

align 512, db 0
