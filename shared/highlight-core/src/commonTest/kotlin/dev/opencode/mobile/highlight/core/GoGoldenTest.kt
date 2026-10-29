package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written Go fixtures: the contract for the C-like lexer in Go. */
class GoGoldenTest {
    @Test
    fun declarationsAndBuiltins() {
        assertGolden(
            "go",
            """
            package main

            import (
                "fmt"
                str "strings"
            )

            const (
                Idle Status = iota
                Busy
            )

            type Store[T any] struct {
                mu    sync.RWMutex
                items map[string]T `json:"items"`
            }

            func (s *Store[T]) Get(id string) (T, bool) {
                s.mu.RLock()
                defer s.mu.RUnlock()
                v, ok := s.items[id]
                if !ok { return *new(T), false }
                ch := make(chan int, 10); go func() { ch <- len(s.items) }()
                var err error = nil
                return v, true
            }
            """.trimIndent(),
            """
            «Keyword|package» «Type|main»

            «Keyword|import» «Punctuation|(»
                «String|"fmt"»
                «Variable|str» «String|"strings"»
            «Punctuation|)»

            «Keyword|const» «Punctuation|(»
                «Type|Idle» «Type|Status» «Operator|=» «Constant|iota»
                «Type|Busy»
            «Punctuation|)»

            «Keyword|type» «Type|Store»«Punctuation|[»«Type|T» «Keyword|any»«Punctuation|]» «Keyword|struct» «Punctuation|{»
                «Variable|mu»    «Variable|sync»«Punctuation|.»«Type|RWMutex»
                «Variable|items» «Keyword|map»«Punctuation|[»«Keyword|string»«Punctuation|]»«Type|T» «String|`json:"items"`»
            «Punctuation|}»

            «Keyword|func» «Punctuation|(»«Variable|s» «Operator|*»«Type|Store»«Punctuation|[»«Type|T»«Punctuation|])» «Function|Get»«Punctuation|(»«Variable|id» «Keyword|string»«Punctuation|)» «Punctuation|(»«Type|T»«Punctuation|,» «Keyword|bool»«Punctuation|)» «Punctuation|{»
                «Variable|s»«Punctuation|.»«Property|mu»«Punctuation|.»«Function|RLock»«Punctuation|()»
                «Keyword|defer» «Variable|s»«Punctuation|.»«Property|mu»«Punctuation|.»«Function|RUnlock»«Punctuation|()»
                «Variable|v»«Punctuation|,» «Variable|ok» «Operator|:=» «Variable|s»«Punctuation|.»«Property|items»«Punctuation|[»«Variable|id»«Punctuation|]»
                «Keyword|if» «Operator|!»«Variable|ok» «Punctuation|{» «Keyword|return» «Operator|*»«Function|new»«Punctuation|(»«Type|T»«Punctuation|),» «Constant|false» «Punctuation|}»
                «Variable|ch» «Operator|:=» «Function|make»«Punctuation|(»«Keyword|chan» «Keyword|int»«Punctuation|,» «Number|10»«Punctuation|);» «Keyword|go» «Keyword|func»«Punctuation|()» «Punctuation|{» «Variable|ch» «Operator|<-» «Function|len»«Punctuation|(»«Variable|s»«Punctuation|.»«Property|items»«Punctuation|)» «Punctuation|}()»
                «Keyword|var» «Variable|err» «Keyword|error» «Operator|=» «Constant|nil»
                «Keyword|return» «Variable|v»«Punctuation|,» «Constant|true»
            «Punctuation|}»
            """.trimIndent(),
        )
    }

    @Test
    fun stringsRunesAndNumbers() {
        assertGolden(
            "golang",
            """
            msg := fmt.Sprintf("hello %s %5.2f %-8v %% %[1]d \n \x41 \101 \u00e9 \U0001F600", name)
            raw := `raw \n "quoted"
            second line %d`
            r := 'x'; e := '\n'; q := '\''; u := '世'
            n := 1_000 + 0x1F + 0o17 + 0b1010 + 3.14 + 1e-9 + 0x1p-2 + 2i + 1.5i + .5
            open := "never closed
            next := 1
            """.trimIndent(),
            """
            «Variable|msg» «Operator|:=» «Variable|fmt»«Punctuation|.»«Function|Sprintf»«Punctuation|(»«String|"hello »«Constant|%s»«String| »«Constant|%5.2f»«String| »«Constant|%-8v»«String| »«Constant|%%»«String| »«Constant|%[1]d»«String| »«Constant|\n»«String| »«Constant|\x41»«String| »«Constant|\101»«String| »«Constant|\u00e9»«String| »«Constant|\U0001F600»«String|"»«Punctuation|,» «Variable|name»«Punctuation|)»
            «Variable|raw» «Operator|:=» «String|`raw \n "quoted"»
            «String|second line %d`»
            «Variable|r» «Operator|:=» «String|'x'»«Punctuation|;» «Variable|e» «Operator|:=» «String|'»«Constant|\n»«String|'»«Punctuation|;» «Variable|q» «Operator|:=» «String|'»«Constant|\'»«String|'»«Punctuation|;» «Variable|u» «Operator|:=» «String|'世'»
            «Variable|n» «Operator|:=» «Number|1_000» «Operator|+» «Number|0x1F» «Operator|+» «Number|0o17» «Operator|+» «Number|0b1010» «Operator|+» «Number|3.14» «Operator|+» «Number|1e-9» «Operator|+» «Number|0x1p-2» «Operator|+» «Number|2i» «Operator|+» «Number|1.5i» «Operator|+» «Number|.5»
            «Variable|open» «Operator|:=» «String|"never closed»
            «Variable|next» «Operator|:=» «Number|1»
            """.trimIndent(),
        )
    }
}
