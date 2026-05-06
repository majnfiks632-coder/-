/*
 * Скрипт компоновщика для ядра ДубинаОС на x86_64
 *
 * Ядро загружается на физический адрес 0x100000 (1 MiB) — это стандартное место
 * куда загрузчик (наш собственный или GRUB через Multiboot2) помещает ядро.
 */

ENTRY(kernel_entry)
OUTPUT_FORMAT(elf64-x86-64)

KERNEL_LMA = 0x100000;

SECTIONS {
    . = KERNEL_LMA;

    /*
     * Точка входа должна находиться по адресу KERNEL_LMA = 0x100000, так как
     * наш собственный загрузчик (Stage2 без GRUB) делает прямой `jmp 0x100000`,
     * а raw-бинарник начинается с самого начала линковки. Поэтому код
     * kernel_entry размещается ПЕРВЫМ.
     */
    /*
     * Заголовок Multiboot2 — должен находиться в первых 32 КиБ ядра и быть
     * выровнен по 8 байтам. Кладём его в самое начало (сразу после стартового
     * стаба `kernel_entry`), чтобы независимо от размера остального кода ядра
     * GRUB всегда находил магию.
     */
    .text : {
        *(.text.kernel_entry)
        . = ALIGN(8);
        KEEP(*(.multiboot_header))
        *(.text .text.*)
    }

    .rodata ALIGN(4K) : {
        *(.rodata .rodata.*)
    }

    .data ALIGN(4K) : {
        *(.data .data.*)
    }

    .bss ALIGN(4K) : {
        . = ALIGN(8);
        bss_start = .;
        *(.bss .bss.*)
        *(COMMON)
        . = ALIGN(8);
        bss_end = .;
    }

    kernel_end = .;

    /DISCARD/ : {
        *(.eh_frame)
        *(.note.GNU-stack)
        *(.comment)
    }
}
