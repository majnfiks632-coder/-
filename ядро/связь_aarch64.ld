/*
 * Скрипт компоновщика для ядра ДубинаОС на aarch64.
 *
 * QEMU virt-машина загружает ядро по адресу 0x40080000 (kernel start).
 */

ENTRY(kernel_entry)
OUTPUT_FORMAT(elf64-littleaarch64)

KERNEL_LMA = 0x40080000;

SECTIONS {
    . = KERNEL_LMA;

    .text ALIGN(4K) : {
        *(.text.kernel_entry)
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
