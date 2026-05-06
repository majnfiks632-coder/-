/*
 * ДубинаОС — ASM-трамплины прерываний для x86_64.
 *
 * Внешние имена символов — ASCII (требование `extern "C"` в Rust).
 * Внутренние комментарии и описание логики — на русском.
 */
.section .text
.code64

# Сохранение всех 15 регистров общего назначения.
.macro SAVE_REGS
    push rax
    push rbx
    push rcx
    push rdx
    push rsi
    push rdi
    push rbp
    push r8
    push r9
    push r10
    push r11
    push r12
    push r13
    push r14
    push r15
.endm

.macro RESTORE_REGS
    pop r15
    pop r14
    pop r13
    pop r12
    pop r11
    pop r10
    pop r9
    pop r8
    pop rbp
    pop rdi
    pop rsi
    pop rdx
    pop rcx
    pop rbx
    pop rax
.endm

# Исключение БЕЗ кода ошибки.
.macro EXC_NOERR num
.global _dub_exc_\num
_dub_exc_\num:
    push 0
    push \num
    SAVE_REGS
    mov rdi, rsp
    cld
    call _dub_exception_handler
    RESTORE_REGS
    add rsp, 16
    iretq
.endm

# Исключение С кодом ошибки.
.macro EXC_ERR num
.global _dub_exc_\num
_dub_exc_\num:
    push \num
    SAVE_REGS
    mov rdi, rsp
    cld
    call _dub_exception_handler
    RESTORE_REGS
    add rsp, 16
    iretq
.endm

# IRQ.
.macro IRQ_TRAMP num
.global _dub_irq_\num
_dub_irq_\num:
    push 0
    push (32 + \num)
    SAVE_REGS
    mov rdi, rsp
    cld
    call _dub_irq_handler
    RESTORE_REGS
    add rsp, 16
    iretq
.endm

EXC_NOERR 0
EXC_NOERR 1
EXC_NOERR 2
EXC_NOERR 3
EXC_NOERR 4
EXC_NOERR 5
EXC_NOERR 6
EXC_NOERR 7
EXC_ERR   8
EXC_NOERR 9
EXC_ERR   10
EXC_ERR   11
EXC_ERR   12
EXC_ERR   13
EXC_ERR   14
EXC_NOERR 16
EXC_ERR   17
EXC_NOERR 18
EXC_NOERR 19
EXC_NOERR 20

IRQ_TRAMP 0
IRQ_TRAMP 1
IRQ_TRAMP 2
IRQ_TRAMP 3
IRQ_TRAMP 4
IRQ_TRAMP 5
IRQ_TRAMP 6
IRQ_TRAMP 7
IRQ_TRAMP 8
IRQ_TRAMP 9
IRQ_TRAMP 10
IRQ_TRAMP 11
IRQ_TRAMP 12
IRQ_TRAMP 13
IRQ_TRAMP 14
IRQ_TRAMP 15
