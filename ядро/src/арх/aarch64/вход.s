/*
 * Точка входа ядра ДубинаОС для aarch64.
 *
 * QEMU `virt` загружает ядро по адресу 0x40080000 и стартует первое ядро в EL2 (или EL1
 * с -kernel). Здесь выполняем минимальную настройку и переходим в Rust _yadro_start.
 */

.section .text.kernel_entry, "ax"
.global kernel_entry
.type kernel_entry, @function
kernel_entry:
    // Если мы не на CPU0 — паркуемся (для SMP-загрузки потом).
    mrs     x0, mpidr_el1
    and     x0, x0, #0xFF
    cbnz    x0, parking

    // Включаем FP/SIMD на EL1 (без этого Rust-код, использующий NEON
    // — например, проверки выравнивания в `core::ptr::write_volatile` —
    // вызывает исключение, и без VBAR_EL1 процессор виснет).
    mrs     x0, cpacr_el1
    orr     x0, x0, #(3 << 20)        // CPACR_EL1.FPEN = 0b11 (без ловушек)
    msr     cpacr_el1, x0
    isb

    // Устанавливаем стек.
    adrp    x0, stack_top
    add     x0, x0, :lo12:stack_top
    mov     sp, x0

    // Очищаем .bss перед использованием.
    adrp    x0, bss_start
    add     x0, x0, :lo12:bss_start
    adrp    x1, bss_end
    add     x1, x1, :lo12:bss_end
clear_bss:
    cmp     x0, x1
    b.eq    bss_done
    str     xzr, [x0], #8
    b       clear_bss
bss_done:

    // Магия и инфо: для aarch64 загрузчика передаём 0 (пока).
    mov     x0, #0
    mov     x1, #0
    bl      _yadro_start

parking:
    wfe
    b       parking

// Стек 64 КБ.
.section .bss
.balign 16
stack_bottom:
    .skip 65536
.global stack_top
stack_top:
