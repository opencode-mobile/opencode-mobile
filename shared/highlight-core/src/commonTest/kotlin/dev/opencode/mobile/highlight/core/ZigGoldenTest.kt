package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written Zig fixtures: the contract for the C-like lexer in Zig. */
class ZigGoldenTest {
    @Test
    fun functionsStructsAndLineStrings() {
        assertGolden(
            "zig",
            """
            const std = @import("std");

            /// Doc comment
            pub fn label(count: u32) []const u8 {
                return std.fmt.allocPrint(std.heap.page_allocator, "sessions: {d}", .{count}) catch "sessions: ?";
            }

            const Point = struct {
                x: i32 = 0,
                y: u7,
                pub fn init() Point { return .{ .x = 1, .y = 2 }; }
            };

            test "label works" {
                const text =
                    \\multi line
                    \\  string "with quotes" // not a comment
                ;
                var c: u8 = 'a';
                const e = '\n';
                comptime var i: usize = 0x1F + 1_000 + 0o17 + 0b101 + 1.5e3;
                const @"weird name" = undefined;
                if (x / 2 * 3 == 0) unreachable;
                errdefer |err| std.log.err("{}", .{err});
                const opt: ?*Point = null;
                _ = opt orelse return error.Missing;
            }
            """.trimIndent(),
            """
            «Keyword|const» «Variable|std» «Operator|=» «Function|@import»«Punctuation|(»«String|"std"»«Punctuation|);»

            «Comment|/// Doc comment»
            «Keyword|pub» «Keyword|fn» «Function|label»«Punctuation|(»«Variable|count»«Punctuation|:» «Keyword|u32»«Punctuation|)» «Punctuation|[]»«Keyword|const» «Keyword|u8» «Punctuation|{»
                «Keyword|return» «Variable|std»«Punctuation|.»«Property|fmt»«Punctuation|.»«Function|allocPrint»«Punctuation|(»«Variable|std»«Punctuation|.»«Property|heap»«Punctuation|.»«Property|page_allocator»«Punctuation|,» «String|"sessions: {d}"»«Punctuation|,» «Punctuation|.{»«Variable|count»«Punctuation|})» «Keyword|catch» «String|"sessions: ?"»«Punctuation|;»
            «Punctuation|}»

            «Keyword|const» «Type|Point» «Operator|=» «Keyword|struct» «Punctuation|{»
                «Variable|x»«Punctuation|:» «Keyword|i32» «Operator|=» «Number|0»«Punctuation|,»
                «Variable|y»«Punctuation|:» «Keyword|u7»«Punctuation|,»
                «Keyword|pub» «Keyword|fn» «Function|init»«Punctuation|()» «Type|Point» «Punctuation|{» «Keyword|return» «Punctuation|.{» «Punctuation|.»«Property|x» «Operator|=» «Number|1»«Punctuation|,» «Punctuation|.»«Property|y» «Operator|=» «Number|2» «Punctuation|};» «Punctuation|}»
            «Punctuation|};»

            «Keyword|test» «String|"label works"» «Punctuation|{»
                «Keyword|const» «Variable|text» «Operator|=»
                    «String|\\multi line»
                    «String|\\  string "with quotes" // not a comment»
                «Punctuation|;»
                «Keyword|var» «Variable|c»«Punctuation|:» «Keyword|u8» «Operator|=» «String|'a'»«Punctuation|;»
                «Keyword|const» «Variable|e» «Operator|=» «String|'»«Constant|\n»«String|'»«Punctuation|;»
                «Keyword|comptime» «Keyword|var» «Variable|i»«Punctuation|:» «Keyword|usize» «Operator|=» «Number|0x1F» «Operator|+» «Number|1_000» «Operator|+» «Number|0o17» «Operator|+» «Number|0b101» «Operator|+» «Number|1.5e3»«Punctuation|;»
                «Keyword|const» «Variable|@"weird name"» «Operator|=» «Constant|undefined»«Punctuation|;»
                «Keyword|if» «Punctuation|(»«Variable|x» «Operator|/» «Number|2» «Operator|*» «Number|3» «Operator|==» «Number|0»«Punctuation|)» «Keyword|unreachable»«Punctuation|;»
                «Keyword|errdefer» «Operator||»«Variable|err»«Operator||» «Variable|std»«Punctuation|.»«Property|log»«Punctuation|.»«Function|err»«Punctuation|(»«String|"{}"»«Punctuation|,» «Punctuation|.{»«Variable|err»«Punctuation|});»
                «Keyword|const» «Variable|opt»«Punctuation|:» «Operator|?*»«Type|Point» «Operator|=» «Constant|null»«Punctuation|;»
                «Variable|_» «Operator|=» «Variable|opt» «Keyword|orelse» «Keyword|return» «Keyword|error»«Punctuation|.»«Type|Missing»«Punctuation|;»
            «Punctuation|}»
            """.trimIndent(),
        )
    }
}
