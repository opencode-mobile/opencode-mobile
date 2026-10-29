package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written TypeScript and JavaScript fixtures: the contract for the C-like lexer in those languages. */
class TypeScriptGoldenTest {
    @Test
    fun regexOrDivisionFromThePreviousToken() {
        assertGolden(
            "typescript",
            """
            // regex or division
            const re = /[/\]]+\/x/gi, half = total / 2 / count;
            if (/^a/.test(s)) x = (a + b) / c;
            const r = cond ? /x/ : y;
            return /done/;
            const s = a.b / c[0] / d;
            const t = typeof /x/;
            let bad = 1 /[ 2;
            let ok = /ok/;
            """.trimIndent(),
            """
            «Comment|// regex or division»
            «Keyword|const» «Variable|re» «Operator|=» «String|/[/\]]+\/x/gi»«Punctuation|,» «Variable|half» «Operator|=» «Variable|total» «Operator|/» «Number|2» «Operator|/» «Variable|count»«Punctuation|;»
            «Keyword|if» «Punctuation|(»«String|/^a/»«Punctuation|.»«Function|test»«Punctuation|(»«Variable|s»«Punctuation|))» «Variable|x» «Operator|=» «Punctuation|(»«Variable|a» «Operator|+» «Variable|b»«Punctuation|)» «Operator|/» «Variable|c»«Punctuation|;»
            «Keyword|const» «Variable|r» «Operator|=» «Variable|cond» «Operator|?» «String|/x/» «Punctuation|:» «Variable|y»«Punctuation|;»
            «Keyword|return» «String|/done/»«Punctuation|;»
            «Keyword|const» «Variable|s» «Operator|=» «Variable|a»«Punctuation|.»«Property|b» «Operator|/» «Variable|c»«Punctuation|[»«Number|0»«Punctuation|]» «Operator|/» «Variable|d»«Punctuation|;»
            «Keyword|const» «Variable|t» «Operator|=» «Keyword|typeof» «String|/x/»«Punctuation|;»
            «Keyword|let» «Variable|bad» «Operator|=» «Number|1» «Operator|/»«Punctuation|[» «Number|2»«Punctuation|;»
            «Keyword|let» «Variable|ok» «Operator|=» «String|/ok/»«Punctuation|;»
            """.trimIndent(),
        )
    }

    @Test
    fun numbersOperatorsAndMemberAccess() {
        assertGolden(
            "ts",
            """
            let n = [0x1F_FF, 0b1010, 0o17, 1_000_000, 1.5e-3, .25, 10n, 0xFFn, 3e8, 1.];
            let m = a.b.c[1].d?.e!.f;
            x ??= y?.z ?? 0; a >>>= 2; b **= 3; i++; --j;
            const { a, b: [c, ...rest] } = obj;
            """.trimIndent(),
            """
            «Keyword|let» «Variable|n» «Operator|=» «Punctuation|[»«Number|0x1F_FF»«Punctuation|,» «Number|0b1010»«Punctuation|,» «Number|0o17»«Punctuation|,» «Number|1_000_000»«Punctuation|,» «Number|1.5e-3»«Punctuation|,» «Number|.25»«Punctuation|,» «Number|10n»«Punctuation|,» «Number|0xFFn»«Punctuation|,» «Number|3e8»«Punctuation|,» «Number|1»«Punctuation|.];»
            «Keyword|let» «Variable|m» «Operator|=» «Variable|a»«Punctuation|.»«Property|b»«Punctuation|.»«Property|c»«Punctuation|[»«Number|1»«Punctuation|].»«Property|d»«Operator|?»«Punctuation|.»«Property|e»«Operator|!»«Punctuation|.»«Property|f»«Punctuation|;»
            «Variable|x» «Operator|??=» «Variable|y»«Operator|?»«Punctuation|.»«Property|z» «Operator|??» «Number|0»«Punctuation|;» «Variable|a» «Operator|>>>=» «Number|2»«Punctuation|;» «Variable|b» «Operator|**=» «Number|3»«Punctuation|;» «Variable|i»«Operator|++»«Punctuation|;» «Operator|--»«Variable|j»«Punctuation|;»
            «Keyword|const» «Punctuation|{» «Variable|a»«Punctuation|,» «Property|b»«Punctuation|:» «Punctuation|[»«Variable|c»«Punctuation|,» «Operator|...»«Variable|rest»«Punctuation|]» «Punctuation|}» «Operator|=» «Variable|obj»«Punctuation|;»
            """.trimIndent(),
        )
    }

    @Test
    fun stringsEscapesAndTemplatesAcrossLines() {
        assertGolden(
            "typescript",
            """
            const s = 'it\'s' + "tab\tq\"" + "\u00e9\u{1F600}\x41" + 'open
            const next = 1;
            const tpl = `head ${'$'}{a + `in ${'$'}{b ? `deep ${'$'}{c}` : ""}`} tail`;
            const multi = `one
            two ${'$'}{
              items.map((x) => `<${'$'}{x}>`).join(",")
            } three
            four`;
            """.trimIndent(),
            """
            «Keyword|const» «Variable|s» «Operator|=» «String|'it»«Constant|\'»«String|s'» «Operator|+» «String|"tab»«Constant|\t»«String|q»«Constant|\"»«String|"» «Operator|+» «String|"»«Constant|\u00e9\u{1F600}\x41»«String|"» «Operator|+» «String|'open»
            «Keyword|const» «Variable|next» «Operator|=» «Number|1»«Punctuation|;»
            «Keyword|const» «Variable|tpl» «Operator|=» «String|`head »«Punctuation|${'$'}{»«Variable|a» «Operator|+» «String|`in »«Punctuation|${'$'}{»«Variable|b» «Operator|?» «String|`deep ${'$'}{c}`» «Punctuation|:» «String|""»«Punctuation|}»«String|`»«Punctuation|}»«String| tail`»«Punctuation|;»
            «Keyword|const» «Variable|multi» «Operator|=» «String|`one»
            «String|two »«Punctuation|${'$'}{»
              «Variable|items»«Punctuation|.»«Function|map»«Punctuation|((»«Variable|x»«Punctuation|)» «Operator|=>» «String|`<»«Punctuation|${'$'}{»«Variable|x»«Punctuation|}»«String|>`»«Punctuation|).»«Function|join»«Punctuation|(»«String|","»«Punctuation|)»
            «Punctuation|}»«String| three»
            «String|four`»«Punctuation|;»
            """.trimIndent(),
        )
    }

    @Test
    fun lineAndBlockComments() {
        assertGolden(
            "typescript",
            """
            /* block */ let a = 1; /* across
               lines `not a template` */ let b = /* inline */ 2;
            /** doc
             * @param x the x
             */
            """.trimIndent(),
            """
            «Comment|/* block */» «Keyword|let» «Variable|a» «Operator|=» «Number|1»«Punctuation|;» «Comment|/* across»
            «Comment|   lines `not a template` */» «Keyword|let» «Variable|b» «Operator|=» «Comment|/* inline */» «Number|2»«Punctuation|;»
            «Comment|/** doc»
            «Comment| * @param x the x»
            «Comment| */»
            """.trimIndent(),
        )
    }

    @Test
    fun declarationsTypesAndContextualKeywords() {
        assertGolden(
            "typescript",
            """
            import type { Session } from "./types";
            export abstract class Store<T extends Session = Session> implements Base {
              private readonly items = new Map<string, T>();
              #count = 0;
              static readonly MAX_ITEMS = 10;
              declare name: string;
              constructor(private options: Options) { super(); }
              async load(id: string): Promise<T | undefined> { return this.items.get(id); }
              get size(): number { return this.#count; }
              set size(value: number) {}
            }
            @Component({ selector: "app-root" })
            export class AppComponent {}
            type Pair<K, V> = [K, V] | null;
            interface Point { x: number; y?: number; readonly z: unknown }
            enum Color { Red, Green = "g" }
            declare module "x" {}
            namespace NS { export const v = 1 as const; }
            function isString(x: any): x is string { return typeof x === "string"; }
            const type = "t"; const async = 1; let of = from;
            for (const item of list) await process(item);
            const o = { key: 1, "quoted": 2, nested: { inner: true }, short, [computed]: 3, method() {} };
            """.trimIndent(),
            """
            «Keyword|import» «Keyword|type» «Punctuation|{» «Type|Session» «Punctuation|}» «Keyword|from» «String|"./types"»«Punctuation|;»
            «Keyword|export» «Keyword|abstract» «Keyword|class» «Type|Store»«Operator|<»«Type|T» «Keyword|extends» «Type|Session» «Operator|=» «Type|Session»«Operator|>» «Keyword|implements» «Type|Base» «Punctuation|{»
              «Keyword|private» «Keyword|readonly» «Variable|items» «Operator|=» «Keyword|new» «Type|Map»«Operator|<»«Type|string»«Punctuation|,» «Type|T»«Operator|>»«Punctuation|();»
              «Variable|#count» «Operator|=» «Number|0»«Punctuation|;»
              «Keyword|static» «Keyword|readonly» «Constant|MAX_ITEMS» «Operator|=» «Number|10»«Punctuation|;»
              «Keyword|declare» «Variable|name»«Punctuation|:» «Type|string»«Punctuation|;»
              «Keyword|constructor»«Punctuation|(»«Keyword|private» «Variable|options»«Punctuation|:» «Type|Options»«Punctuation|)» «Punctuation|{» «Keyword|super»«Punctuation|();» «Punctuation|}»
              «Keyword|async» «Function|load»«Punctuation|(»«Variable|id»«Punctuation|:» «Type|string»«Punctuation|):» «Type|Promise»«Operator|<»«Type|T» «Operator||» «Constant|undefined»«Operator|>» «Punctuation|{» «Keyword|return» «Keyword|this»«Punctuation|.»«Property|items»«Punctuation|.»«Function|get»«Punctuation|(»«Variable|id»«Punctuation|);» «Punctuation|}»
              «Keyword|get» «Function|size»«Punctuation|():» «Type|number» «Punctuation|{» «Keyword|return» «Keyword|this»«Punctuation|.»«Property|#count»«Punctuation|;» «Punctuation|}»
              «Keyword|set» «Function|size»«Punctuation|(»«Variable|value»«Punctuation|:» «Type|number»«Punctuation|)» «Punctuation|{}»
            «Punctuation|}»
            «Attribute|@Component»«Punctuation|({» «Property|selector»«Punctuation|:» «String|"app-root"» «Punctuation|})»
            «Keyword|export» «Keyword|class» «Type|AppComponent» «Punctuation|{}»
            «Keyword|type» «Type|Pair»«Operator|<»«Type|K»«Punctuation|,» «Type|V»«Operator|>» «Operator|=» «Punctuation|[»«Type|K»«Punctuation|,» «Type|V»«Punctuation|]» «Operator||» «Constant|null»«Punctuation|;»
            «Keyword|interface» «Type|Point» «Punctuation|{» «Property|x»«Punctuation|:» «Type|number»«Punctuation|;» «Property|y»«Operator|?:» «Type|number»«Punctuation|;» «Keyword|readonly» «Variable|z»«Punctuation|:» «Type|unknown» «Punctuation|}»
            «Keyword|enum» «Type|Color» «Punctuation|{» «Type|Red»«Punctuation|,» «Type|Green» «Operator|=» «String|"g"» «Punctuation|}»
            «Keyword|declare» «Keyword|module» «String|"x"» «Punctuation|{}»
            «Keyword|namespace» «Type|NS» «Punctuation|{» «Keyword|export» «Keyword|const» «Variable|v» «Operator|=» «Number|1» «Keyword|as» «Keyword|const»«Punctuation|;» «Punctuation|}»
            «Keyword|function» «Function|isString»«Punctuation|(»«Variable|x»«Punctuation|:» «Type|any»«Punctuation|):» «Variable|x» «Keyword|is» «Type|string» «Punctuation|{» «Keyword|return» «Keyword|typeof» «Variable|x» «Operator|===» «String|"string"»«Punctuation|;» «Punctuation|}»
            «Keyword|const» «Variable|type» «Operator|=» «String|"t"»«Punctuation|;» «Keyword|const» «Variable|async» «Operator|=» «Number|1»«Punctuation|;» «Keyword|let» «Variable|of» «Operator|=» «Variable|from»«Punctuation|;»
            «Keyword|for» «Punctuation|(»«Keyword|const» «Variable|item» «Keyword|of» «Variable|list»«Punctuation|)» «Keyword|await» «Function|process»«Punctuation|(»«Variable|item»«Punctuation|);»
            «Keyword|const» «Variable|o» «Operator|=» «Punctuation|{» «Property|key»«Punctuation|:» «Number|1»«Punctuation|,» «String|"quoted"»«Punctuation|:» «Number|2»«Punctuation|,» «Property|nested»«Punctuation|:» «Punctuation|{» «Property|inner»«Punctuation|:» «Constant|true» «Punctuation|},» «Variable|short»«Punctuation|,» «Punctuation|[»«Variable|computed»«Punctuation|]:» «Number|3»«Punctuation|,» «Function|method»«Punctuation|()» «Punctuation|{}» «Punctuation|};»
            """.trimIndent(),
        )
    }

    @Test
    fun tsxElementsAttributesAndTypeParameters() {
        assertGolden(
            "tsx",
            """
            export function List({ items }: Props) {
              return (
                <ul className="list" data-count={items.length}>
                  {items.map((item) => (
                    <Item key={item.id} {...item} onSelect={() => select(item)} />
                  ))}
                  <>
                    <b>bold</b> text with 'quotes' & {"braces"}
                  </>
                  <Foo.Bar
                    title={`Hi ${'$'}{name}`}
                    disabled
                  />
                </ul>
              );
            }
            const id = <T,>(x: T) => x;
            const map = <T>(x: T) => [x];
            const k = <K extends string>(key: K) => key;
            const less = a < b && c > d;
            const el = cond ? <span>yes</span> : <span>no</span>;
            """.trimIndent(),
            """
            «Keyword|export» «Keyword|function» «Function|List»«Punctuation|({» «Variable|items» «Punctuation|}:» «Type|Props»«Punctuation|)» «Punctuation|{»
              «Keyword|return» «Punctuation|(»
                «Punctuation|<»«Tag|ul» «Attribute|className»«Operator|=»«String|"list"» «Attribute|data-count»«Operator|=»«Punctuation|{»«Variable|items»«Punctuation|.»«Property|length»«Punctuation|}>»
                  «Punctuation|{»«Variable|items»«Punctuation|.»«Function|map»«Punctuation|((»«Variable|item»«Punctuation|)» «Operator|=>» «Punctuation|(»
                    «Punctuation|<»«Tag|Item» «Attribute|key»«Operator|=»«Punctuation|{»«Variable|item»«Punctuation|.»«Property|id»«Punctuation|}» «Punctuation|{»«Operator|...»«Variable|item»«Punctuation|}» «Attribute|onSelect»«Operator|=»«Punctuation|{()» «Operator|=>» «Function|select»«Punctuation|(»«Variable|item»«Punctuation|)}» «Punctuation|/>»
                  «Punctuation|))}»
                  «Punctuation|<>»
                    «Punctuation|<»«Tag|b»«Punctuation|>»bold«Punctuation|</»«Tag|b»«Punctuation|>» text with 'quotes' & «Punctuation|{»«String|"braces"»«Punctuation|}»
                  «Punctuation|</>»
                  «Punctuation|<»«Tag|Foo.Bar»
                    «Attribute|title»«Operator|=»«Punctuation|{»«String|`Hi »«Punctuation|${'$'}{»«Variable|name»«Punctuation|}»«String|`»«Punctuation|}»
                    «Attribute|disabled»
                  «Punctuation|/>»
                «Punctuation|</»«Tag|ul»«Punctuation|>»
              «Punctuation|);»
            «Punctuation|}»
            «Keyword|const» «Variable|id» «Operator|=» «Operator|<»«Type|T»«Punctuation|,»«Operator|>»«Punctuation|(»«Variable|x»«Punctuation|:» «Type|T»«Punctuation|)» «Operator|=>» «Variable|x»«Punctuation|;»
            «Keyword|const» «Variable|map» «Operator|=» «Operator|<»«Type|T»«Operator|>»«Punctuation|(»«Variable|x»«Punctuation|:» «Type|T»«Punctuation|)» «Operator|=>» «Punctuation|[»«Variable|x»«Punctuation|];»
            «Keyword|const» «Variable|k» «Operator|=» «Operator|<»«Type|K» «Keyword|extends» «Type|string»«Operator|>»«Punctuation|(»«Variable|key»«Punctuation|:» «Type|K»«Punctuation|)» «Operator|=>» «Variable|key»«Punctuation|;»
            «Keyword|const» «Variable|less» «Operator|=» «Variable|a» «Operator|<» «Variable|b» «Operator|&&» «Variable|c» «Operator|>» «Variable|d»«Punctuation|;»
            «Keyword|const» «Variable|el» «Operator|=» «Variable|cond» «Operator|?» «Punctuation|<»«Tag|span»«Punctuation|>»yes«Punctuation|</»«Tag|span»«Punctuation|>» «Punctuation|:» «Punctuation|<»«Tag|span»«Punctuation|>»no«Punctuation|</»«Tag|span»«Punctuation|>;»
            """.trimIndent(),
        )
    }

    @Test
    fun nestingPastFourFramesDegradesInsideTheLine() {
        assertGolden(
            "typescript",
            """
            const deep = `1${'$'}{`2${'$'}{`3${'$'}{`4${'$'}{x}`}`}`}`;
            const after = 1;
            """.trimIndent(),
            """
            «Keyword|const» «Variable|deep» «Operator|=» «String|`1»«Punctuation|${'$'}{»«String|`2»«Punctuation|${'$'}{»«String|`3${'$'}{`»«Number|4»«Variable|${'$'}»«Punctuation|{»«Variable|x»«Punctuation|}»«String|`}`»«Punctuation|}»«String|`»«Punctuation|}»«String|`»«Punctuation|;»
            «Keyword|const» «Variable|after» «Operator|=» «Number|1»«Punctuation|;»
            """.trimIndent(),
        )
    }

    @Test
    fun javaScriptHashbangPrivateNamesAndJsx() {
        assertGolden(
            "javascript",
            """
            #!/usr/bin/env node
            "use strict";
            class Client {
              #baseUrl;
              constructor(url) { this.#baseUrl = url; }
              static #make() { return new Client(`${'$'}{this.#baseUrl}/x`); }
            }
            const view = <div onClick={handler}>{label}</div>;
            void 0; delete obj[key]; x instanceof Y; "k" in o;
            """.trimIndent(),
            """
            «Comment|#!/usr/bin/env node»
            «String|"use strict"»«Punctuation|;»
            «Keyword|class» «Type|Client» «Punctuation|{»
              «Variable|#baseUrl»«Punctuation|;»
              «Keyword|constructor»«Punctuation|(»«Variable|url»«Punctuation|)» «Punctuation|{» «Keyword|this»«Punctuation|.»«Property|#baseUrl» «Operator|=» «Variable|url»«Punctuation|;» «Punctuation|}»
              «Keyword|static» «Function|#make»«Punctuation|()» «Punctuation|{» «Keyword|return» «Keyword|new» «Function|Client»«Punctuation|(»«String|`»«Punctuation|${'$'}{»«Keyword|this»«Punctuation|.»«Property|#baseUrl»«Punctuation|}»«String|/x`»«Punctuation|);» «Punctuation|}»
            «Punctuation|}»
            «Keyword|const» «Variable|view» «Operator|=» «Punctuation|<»«Tag|div» «Attribute|onClick»«Operator|=»«Punctuation|{»«Variable|handler»«Punctuation|}>{»«Variable|label»«Punctuation|}</»«Tag|div»«Punctuation|>;»
            «Keyword|void» «Number|0»«Punctuation|;» «Keyword|delete» «Variable|obj»«Punctuation|[»«Variable|key»«Punctuation|];» «Variable|x» «Keyword|instanceof» «Type|Y»«Punctuation|;» «String|"k"» «Keyword|in» «Variable|o»«Punctuation|;»
            """.trimIndent(),
        )
    }
}
