package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written Rust fixtures: the contract for the C-like lexer in Rust. */
class RustGoldenTest {
    @Test
    fun commentsAttributesAndMacros() {
        assertGolden(
            "rust",
            """
            //! Crate docs
            /// Item docs
            /* outer /* inner */ still outer */ mod store;
            /* open /* nested
               */ still comment
            */ use std::collections::HashMap;
            #![allow(dead_code)]
            #[derive(Debug, Clone, PartialEq)]
            #[cfg_attr(test, serde::rename = "x")]
            pub struct Store<'a, T: Clone> {
                name: &'a str,
                items: Vec<T>,
            }
            macro_rules! square { (${'$'}x:expr) => { ${'$'}x * ${'$'}x }; }
            fn main() { println!("{} {{x}}", 1); assert_eq!(a, b); let v = vec![1, 2]; let ok = a != b; }
            """.trimIndent(),
            """
            «Comment|//! Crate docs»
            «Comment|/// Item docs»
            «Comment|/* outer /* inner */ still outer */» «Keyword|mod» «Variable|store»«Punctuation|;»
            «Comment|/* open /* nested»
            «Comment|   */ still comment»
            «Comment|*/» «Keyword|use» «Variable|std»«Operator|::»«Variable|collections»«Operator|::»«Type|HashMap»«Punctuation|;»
            «Punctuation|#![»«Attribute|allow»«Punctuation|(»«Variable|dead_code»«Punctuation|)]»
            «Punctuation|#[»«Attribute|derive»«Punctuation|(»«Type|Debug»«Punctuation|,» «Type|Clone»«Punctuation|,» «Type|PartialEq»«Punctuation|)]»
            «Punctuation|#[»«Attribute|cfg_attr»«Punctuation|(»«Variable|test»«Punctuation|,» «Variable|serde»«Operator|::»«Variable|rename» «Operator|=» «String|"x"»«Punctuation|)]»
            «Keyword|pub» «Keyword|struct» «Type|Store»«Operator|<»«Type|'a»«Punctuation|,» «Type|T»«Operator|:» «Type|Clone»«Operator|>» «Punctuation|{»
                «Variable|name»«Operator|:» «Operator|&»«Type|'a» «Type|str»«Punctuation|,»
                «Variable|items»«Operator|:» «Type|Vec»«Operator|<»«Type|T»«Operator|>»«Punctuation|,»
            «Punctuation|}»
            «Function|macro_rules!» «Variable|square» «Punctuation|{» «Punctuation|(»«Variable|${'$'}x»«Operator|:»«Variable|expr»«Punctuation|)» «Operator|=>» «Punctuation|{» «Variable|${'$'}x» «Operator|*» «Variable|${'$'}x» «Punctuation|};» «Punctuation|}»
            «Keyword|fn» «Function|main»«Punctuation|()» «Punctuation|{» «Function|println!»«Punctuation|(»«String|"»«Punctuation|{}»«String| {{x}}"»«Punctuation|,» «Number|1»«Punctuation|);» «Function|assert_eq!»«Punctuation|(»«Variable|a»«Punctuation|,» «Variable|b»«Punctuation|);» «Keyword|let» «Variable|v» «Operator|=» «Function|vec!»«Punctuation|[»«Number|1»«Punctuation|,» «Number|2»«Punctuation|];» «Keyword|let» «Variable|ok» «Operator|=» «Variable|a» «Operator|!=» «Variable|b»«Punctuation|;» «Punctuation|}»
            """.trimIndent(),
        )
    }

    @Test
    fun declarationsNumbersAndPaths() {
        assertGolden(
            "rs",
            """
            use std::fmt::{self, Display};

            pub enum Status { Idle, Failed(String) }
            pub trait Render: Display { fn render(&self) -> String; }
            impl<T> Render for Store<T> where T: Copy {
                const MAX: usize = 1_024;
                pub async fn load(&mut self, id: u64) -> Result<Option<T>, Error> {
                    let n = 1_000u32 + 0xFFu8 + 0o17 + 0b1010_i64 + 2.5f64 + 1e10 + 3.0E-2 + 7.;
                    let t = self.items.0; let range = 0..=10;
                    match id { 0 => Ok(None), n @ 1..=9 => Err(Error::new(n)), _ => todo!() }
                    Self::new().await?;
                    let closure = |x: i32| -> i32 { x + 1 };
                    Some(Status::Failed(String::from("x")))
                }
            }
            """.trimIndent(),
            """
            «Keyword|use» «Variable|std»«Operator|::»«Variable|fmt»«Operator|::»«Punctuation|{»«Variable|self»«Punctuation|,» «Type|Display»«Punctuation|};»

            «Keyword|pub» «Keyword|enum» «Type|Status» «Punctuation|{» «Type|Idle»«Punctuation|,» «Type|Failed»«Punctuation|(»«Type|String»«Punctuation|)» «Punctuation|}»
            «Keyword|pub» «Keyword|trait» «Type|Render»«Operator|:» «Type|Display» «Punctuation|{» «Keyword|fn» «Function|render»«Punctuation|(»«Operator|&»«Variable|self»«Punctuation|)» «Operator|->» «Type|String»«Punctuation|;» «Punctuation|}»
            «Keyword|impl»«Operator|<»«Type|T»«Operator|>» «Type|Render» «Keyword|for» «Type|Store»«Operator|<»«Type|T»«Operator|>» «Keyword|where» «Type|T»«Operator|:» «Type|Copy» «Punctuation|{»
                «Keyword|const» «Constant|MAX»«Operator|:» «Type|usize» «Operator|=» «Number|1_024»«Punctuation|;»
                «Keyword|pub» «Keyword|async» «Keyword|fn» «Function|load»«Punctuation|(»«Operator|&»«Keyword|mut» «Variable|self»«Punctuation|,» «Variable|id»«Operator|:» «Type|u64»«Punctuation|)» «Operator|->» «Type|Result»«Operator|<»«Type|Option»«Operator|<»«Type|T»«Operator|>»«Punctuation|,» «Type|Error»«Operator|>» «Punctuation|{»
                    «Keyword|let» «Variable|n» «Operator|=» «Number|1_000u32» «Operator|+» «Number|0xFFu8» «Operator|+» «Number|0o17» «Operator|+» «Number|0b1010_i64» «Operator|+» «Number|2.5f64» «Operator|+» «Number|1e10» «Operator|+» «Number|3.0E-2» «Operator|+» «Number|7»«Operator|.»«Punctuation|;»
                    «Keyword|let» «Variable|t» «Operator|=» «Variable|self»«Operator|.»«Property|items»«Operator|.»«Number|0»«Punctuation|;» «Keyword|let» «Variable|range» «Operator|=» «Number|0»«Operator|..=»«Number|10»«Punctuation|;»
                    «Keyword|match» «Variable|id» «Punctuation|{» «Number|0» «Operator|=>» «Type|Ok»«Punctuation|(»«Type|None»«Punctuation|),» «Variable|n» «Operator|@» «Number|1»«Operator|..=»«Number|9» «Operator|=>» «Type|Err»«Punctuation|(»«Type|Error»«Operator|::»«Function|new»«Punctuation|(»«Variable|n»«Punctuation|)),» «Variable|_» «Operator|=>» «Function|todo!»«Punctuation|()» «Punctuation|}»
                    «Type|Self»«Operator|::»«Function|new»«Punctuation|()»«Operator|.»«Keyword|await»«Operator|?»«Punctuation|;»
                    «Keyword|let» «Variable|closure» «Operator|=» «Operator||»«Variable|x»«Operator|:» «Type|i32»«Operator||» «Operator|->» «Type|i32» «Punctuation|{» «Variable|x» «Operator|+» «Number|1» «Punctuation|};»
                    «Type|Some»«Punctuation|(»«Type|Status»«Operator|::»«Type|Failed»«Punctuation|(»«Type|String»«Operator|::»«Function|from»«Punctuation|(»«String|"x"»«Punctuation|)))»
                «Punctuation|}»
            «Punctuation|}»
            """.trimIndent(),
        )
    }

    @Test
    fun stringsCharactersAndLifetimes() {
        assertGolden(
            "rust",
            """
            let s = "Hi {name}, {0:>8} {{lit}} \"q\" \u{1F600} \x41 \
                continued
            and next line";
            let raw = r"C:\path";
            let hashes = r##"a "# b
            "## ;
            let bytes = b"\x00ab"; let br = br#"x"#; let cs = c"text"; let cr = cr"x";
            let chars = ('x', '\n', '\'', '\u{1F600}', b'a', '😀');
            fn longest<'a>(x: &'a str) -> &'static str { 'outer: loop { break 'outer; } }
            let r#type = 1; let r = r; let open = "never closed
            fn after() {}
            """.trimIndent(),
            """
            «Keyword|let» «Variable|s» «Operator|=» «String|"Hi »«Punctuation|{»«String|name»«Punctuation|}»«String|, »«Punctuation|{»«String|0:>8»«Punctuation|}»«String| {{lit}} »«Constant|\"»«String|q»«Constant|\"»«String| »«Constant|\u{1F600}»«String| »«Constant|\x41»«String| »«Constant|\»
            «String|    continued»
            «String|and next line"»«Punctuation|;»
            «Keyword|let» «Variable|raw» «Operator|=» «String|r"C:\path"»«Punctuation|;»
            «Keyword|let» «Variable|hashes» «Operator|=» «String|r##"a "# b»
            «String|"##» «Punctuation|;»
            «Keyword|let» «Variable|bytes» «Operator|=» «String|b"»«Constant|\x00»«String|ab"»«Punctuation|;» «Keyword|let» «Variable|br» «Operator|=» «String|br#"x"#»«Punctuation|;» «Keyword|let» «Variable|cs» «Operator|=» «String|c"text"»«Punctuation|;» «Keyword|let» «Variable|cr» «Operator|=» «String|cr"x"»«Punctuation|;»
            «Keyword|let» «Variable|chars» «Operator|=» «Punctuation|(»«String|'x'»«Punctuation|,» «String|'»«Constant|\n»«String|'»«Punctuation|,» «String|'»«Constant|\'»«String|'»«Punctuation|,» «String|'»«Constant|\u{1F600}»«String|'»«Punctuation|,» «String|b'a'»«Punctuation|,» «String|'😀'»«Punctuation|);»
            «Keyword|fn» «Function|longest»«Operator|<»«Type|'a»«Operator|>»«Punctuation|(»«Variable|x»«Operator|:» «Operator|&»«Type|'a» «Type|str»«Punctuation|)» «Operator|->» «Operator|&»«Type|'static» «Type|str» «Punctuation|{» «Type|'outer»«Operator|:» «Keyword|loop» «Punctuation|{» «Keyword|break» «Type|'outer»«Punctuation|;» «Punctuation|}» «Punctuation|}»
            «Keyword|let» «Variable|r#type» «Operator|=» «Number|1»«Punctuation|;» «Keyword|let» «Variable|r» «Operator|=» «Variable|r»«Punctuation|;» «Keyword|let» «Variable|open» «Operator|=» «String|"never closed»
            «String|fn after() »«Punctuation|{}»
            """.trimIndent(),
        )
    }
}
