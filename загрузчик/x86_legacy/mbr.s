; ДубинаОС — собственный MBR (Master Boot Record).
; Лицензия: GPL-3.0-or-later
;
; Назначение:
;   Этот код помещается в первый сектор (LBA 0) загрузочного диска. BIOS
;   автоматически загружает его по адресу 0x7C00 в режиме реальных адресов
;   (16 бит), и передаёт управление сюда.
;
;   Задачи MBR:
;     1. Выставить сегментные регистры и начальный стек.
;     2. Сохранить номер загрузочного диска (BIOS оставил его в DL).
;     3. Прочитать с диска Stage2 (несколько секторов после MBR)
;        в линейный адрес 0x7E00 — сразу после самого MBR.
;     4. Передать управление Stage2.
;
;   Размер: ровно 512 байт. Из них последние 2 байта — сигнатура 0xAA55,
;   которую BIOS проверяет, прежде чем считать сектор загрузочным.

[BITS 16]
[ORG 0x7C00]

; -------------------------------------------------------------------
; Параметры компоновки. Должны совпадать со скриптом сборки образа.
; -------------------------------------------------------------------
SECTOR_SIZE        equ 512
STAGE2_LOAD_SEG    equ 0x07E0      ; 0x07E0:0000 = линейно 0x7E00
STAGE2_LOAD_OFF    equ 0x0000
STAGE2_START_LBA   equ 1           ; Stage2 идёт сразу после MBR
STAGE2_SECTORS     equ 32          ; читаем 32 сектора = 16 КиБ Stage2 (с запасом)

; -------------------------------------------------------------------
; Вход.
; BIOS передал нам управление с CS:IP = 0000:7C00 и DL = номер диска.
; -------------------------------------------------------------------
start:
    cli                             ; запрещаем прерывания на время настройки
    xor     ax, ax
    mov     ds, ax
    mov     es, ax
    mov     ss, ax
    mov     sp, 0x7C00              ; стек растёт вниз от 0x7C00
    sti

    mov     [boot_drive], dl        ; запоминаем номер диска

    call    init_serial

    mov     si, msg_mbr
    call    print_string

    ; -------------------------------------------------------
    ; Чтение Stage2 через INT 13h, AH=0x42 (LBA Extended Read).
    ; Готовим Disk Address Packet (DAP).
    ; -------------------------------------------------------
    mov     si, dap                 ; DS:SI -> DAP
    mov     ah, 0x42
    mov     dl, [boot_drive]
    int     0x13
    jc      .read_error

    mov     si, msg_jump
    call    print_string

    ; -------------------------------------------------------
    ; Передаём управление Stage2 по адресу STAGE2_LOAD_SEG:STAGE2_LOAD_OFF.
    ; -------------------------------------------------------
    mov     dl, [boot_drive]        ; Stage2 тоже использует номер диска
    jmp     STAGE2_LOAD_SEG:STAGE2_LOAD_OFF

.read_error:
    mov     si, msg_error
    call    print_string
.halt:
    hlt
    jmp     .halt

; -------------------------------------------------------------------
; init_serial: настраивает COM1 (0x3F8) на 38400 бод, 8N1.
; Это нужно, чтобы MBR/Stage2 могли отладочно печататься в QEMU
; с флагом `-serial`. На реальном железе тоже видно через RS-232.
; -------------------------------------------------------------------
init_serial:
    pusha
    mov     dx, 0x3F8 + 1
    mov     al, 0x00                ; IER: запретить прерывания
    out     dx, al
    mov     dx, 0x3F8 + 3
    mov     al, 0x80                ; LCR: DLAB=1
    out     dx, al
    mov     dx, 0x3F8 + 0
    mov     al, 0x03                ; делитель = 3 → 38400 бод
    out     dx, al
    mov     dx, 0x3F8 + 1
    mov     al, 0x00
    out     dx, al
    mov     dx, 0x3F8 + 3
    mov     al, 0x03                ; LCR: 8N1, DLAB=0
    out     dx, al
    mov     dx, 0x3F8 + 2
    mov     al, 0xC7                ; FCR: включить FIFO, очистить, 14b порог
    out     dx, al
    mov     dx, 0x3F8 + 4
    mov     al, 0x0B                ; MCR: DTR, RTS, OUT2
    out     dx, al
    popa
    ret

; -------------------------------------------------------------------
; serial_putc: выводит AL в COM1, ждёт готовности THR (LSR.5 = 1).
; Сохраняет все регистры.
; -------------------------------------------------------------------
serial_putc:
    push    ax
    push    dx
    mov     ah, al                   ; сохранить байт
.wait:
    mov     dx, 0x3F8 + 5            ; LSR
    in      al, dx
    test    al, 0x20                 ; THR Empty?
    jz      .wait
    mov     dx, 0x3F8
    mov     al, ah
    out     dx, al
    pop     dx
    pop     ax
    ret

; -------------------------------------------------------------------
; print_string: выводит ASCII-строку (DS:SI), завершённую нулём,
; одновременно через BIOS Teletype (VGA) и в COM1 (для serial-логов).
; Регистры портятся: AX, BX.
; -------------------------------------------------------------------
print_string:
    pusha
.next:
    lodsb
    test    al, al
    jz      .done
    ; --- VGA (BIOS Teletype) ---
    push    ax
    mov     ah, 0x0E
    mov     bx, 0x0007
    int     0x10
    pop     ax
    ; --- COM1 ---
    call    serial_putc
    jmp     .next
.done:
    popa
    ret

; -------------------------------------------------------------------
; Данные.
; -------------------------------------------------------------------
boot_drive:    db 0

; ASCII-строки (русский в этом сегменте использовать нельзя — BIOS-шрифт CP437).
msg_mbr:       db "DubinaOS MBR: loading stage2...", 13, 10, 0
msg_jump:      db "MBR: stage2 loaded, jumping...", 13, 10, 0
msg_error:     db "MBR: disk read error!", 13, 10, 0

; Disk Address Packet (DAP) для INT 13h AH=42h
align 4
dap:
    db      0x10                    ; size of DAP
    db      0x00                    ; reserved
    dw      STAGE2_SECTORS          ; число секторов
    dw      STAGE2_LOAD_OFF         ; offset
    dw      STAGE2_LOAD_SEG         ; segment
    dq      STAGE2_START_LBA        ; начальный LBA

; -------------------------------------------------------------------
; Сигнатура BIOS должна находиться в байтах 510..511 сектора.
; -------------------------------------------------------------------
times 510 - ($ - $$) db 0
dw 0xAA55
