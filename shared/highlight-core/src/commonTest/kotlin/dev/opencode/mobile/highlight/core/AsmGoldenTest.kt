package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written assembly fixtures: the contract for [AsmLexer]. One escaped string per line keeps `$`, backslashes, CRLF, and lone surrogates exact. */
class AsmGoldenTest {
    @Test
    fun nasmSectionsLabelsAndData() {
        assertGolden(
            "asm",
            goldenLines(
                "section .text",
                "global _start",
                "_start:",
                "    mov eax, 1",
                "    int 0x80",
                "msg db 'Hello', 0, `tab\\t`",
                "len equ \$ - msg",
                "buf times 64 db 0",
                "    lock xchg [rbx], ecx ; swap",
                "    mov byte [rax+4], 0FFh",
                "    mov qword ptr [rsp - 8], 1010b",
                "    rep movsb",
                ".loop: dec rcx",
                "    jnz .loop",
                "%define SIZE 17q",
                "main PROC",
                "main ENDP",
            ),
            goldenLines(
                "«Keyword|section» «Keyword|.text»",
                "«Keyword|global» _start",
                "«Function|_start»«Punctuation|:»",
                "    «Keyword|mov» «Constant|eax»«Punctuation|,» «Number|1»",
                "    «Keyword|int» «Number|0x80»",
                "«Function|msg» «Keyword|db» «String|'Hello'»«Punctuation|,» «Number|0»«Punctuation|,» «String|`tab»«Constant|\\t»«String|`»",
                "«Function|len» «Keyword|equ» «Constant|\$» «Operator|-» msg",
                "«Function|buf» «Keyword|times» «Number|64» «Keyword|db» «Number|0»",
                "    «Keyword|lock» «Keyword|xchg» «Punctuation|[»«Constant|rbx»«Punctuation|],» «Constant|ecx» «Comment|; swap»",
                "    «Keyword|mov» «Keyword|byte» «Punctuation|[»«Constant|rax»«Operator|+»«Number|4»«Punctuation|],» «Number|0FFh»",
                "    «Keyword|mov» «Keyword|qword» «Keyword|ptr» «Punctuation|[»«Constant|rsp» «Operator|-» «Number|8»«Punctuation|],» «Number|1010b»",
                "    «Keyword|rep» «Keyword|movsb»",
                "«Function|.loop»«Punctuation|:» «Keyword|dec» «Constant|rcx»",
                "    «Keyword|jnz» .loop",
                "«Keyword|%define» SIZE «Number|17q»",
                "«Function|main» «Keyword|PROC»",
                "«Function|main» «Keyword|ENDP»",
            ),
        )
    }

    @Test
    fun gasAndArm() {
        assertGolden(
            "asm",
            goldenLines(
                "    .globl main",
                "    .type main, @function",
                "main:",
                "    movl \$1, %eax # att",
                "    movq %r8d, %xmm0",
                "    leaq .LC0(%rip), %rdi",
                "1:  jne 1b",
                "    ldr x0, [x1, #8]   // arm64",
                "    add v0.4s, v1.4s, v2.4s",
                "    mov r0, #0x10 @ arm comment",
                "    add w1, w2, w3, lsl #2",
                "    stp x29, x30, [sp, #-16]!",
                "    li a0, 42",
                "#include <asm.h>",
                "#define X 1",
                "# just a comment",
                "    .section .rodata",
                ".LC0: .string \"hi\\n\"",
            ),
            goldenLines(
                "    «Keyword|.globl» main",
                "    «Keyword|.type» main«Punctuation|,» @function",
                "«Function|main»«Punctuation|:»",
                "    «Keyword|movl» «Punctuation|\$»«Number|1»«Punctuation|,» «Constant|%eax» «Comment|# att»",
                "    «Keyword|movq» «Constant|%r8d»«Punctuation|,» «Constant|%xmm0»",
                "    «Keyword|leaq» .LC0«Punctuation|(»«Constant|%rip»«Punctuation|),» «Constant|%rdi»",
                "«Function|1»«Punctuation|:»  «Keyword|jne» «Number|1b»",
                "    «Keyword|ldr» «Constant|x0»«Punctuation|,» «Punctuation|[»«Constant|x1»«Punctuation|,» «Punctuation|#»«Number|8»«Punctuation|]»   «Comment|// arm64»",
                "    «Keyword|add» «Constant|v0.4s»«Punctuation|,» «Constant|v1.4s»«Punctuation|,» «Constant|v2.4s»",
                "    «Keyword|mov» «Constant|r0»«Punctuation|,» «Punctuation|#»«Number|0x10» «Comment|@ arm comment»",
                "    «Keyword|add» «Constant|w1»«Punctuation|,» «Constant|w2»«Punctuation|,» «Constant|w3»«Punctuation|,» «Keyword|lsl» «Punctuation|#»«Number|2»",
                "    «Keyword|stp» «Constant|x29»«Punctuation|,» «Constant|x30»«Punctuation|,» «Punctuation|[»«Constant|sp»«Punctuation|,» «Punctuation|#»«Operator|-»«Number|16»«Punctuation|]»«Operator|!»",
                "    «Keyword|li» «Constant|a0»«Punctuation|,» «Number|42»",
                "«Keyword|#include» «String|<asm.h>»",
                "«Keyword|#define» X «Number|1»",
                "«Comment|# just a comment»",
                "    «Keyword|.section» «Keyword|.rodata»",
                "«Function|.LC0»«Punctuation|:» «Keyword|.string» «String|\"hi»«Constant|\\n»«String|\"»",
            ),
        )
    }

    @Test
    fun commentsStringsAndUnterminated() {
        assertGolden(
            "asm",
            goldenLines(
                "/* block",
                "still */ nop /* again */ ret",
                "mov eax, \"open",
                "msg: db '😀', \"\uD83D\"\r",
                "@@: jmp @b\r",
                "x dd 1.5e3, -2 /* never",
                "closed",
            ),
            goldenLines(
                "«Comment|/* block»",
                "«Comment|still */» «Keyword|nop» «Comment|/* again */» ret",
                "«Keyword|mov» «Constant|eax»«Punctuation|,» «String|\"open»",
                "«Function|msg»«Punctuation|:» «Keyword|db» «String|'😀'»«Punctuation|,» «String|\"⟨D83D⟩\"»␍",
                "«Function|@@»«Punctuation|:» «Keyword|jmp» @b␍",
                "«Function|x» «Keyword|dd» «Number|1.5e3»«Punctuation|,» «Operator|-»«Number|2» «Comment|/* never»",
                "«Comment|closed»",
            ),
        )
    }
}
