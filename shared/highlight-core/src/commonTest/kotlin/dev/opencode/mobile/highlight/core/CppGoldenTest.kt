package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/** Hand-written C++ fixtures: the contract for the C-like lexer in C++. */
class CppGoldenTest {
    @Test
    fun rawStrings() {
        assertGolden(
            "c++",
            """
            auto plain = R"(raw "string" \n)";
            auto sql = R"sql(SELECT ")" FROM t
            WHERE x = ')' -- )sq"
            )sql";
            auto wide = LR"(w)" u8R"x(u)x" uR"(a)" UR"(b)";
            auto notRaw = R "x";
            auto bad = R"no space(x)no space";
            auto open = R"end(never closed
            still raw
            """.trimIndent(),
            """
            «Keyword|auto» plain «Operator|=» «String|R"(raw "string" \n)"»«Punctuation|;»
            «Keyword|auto» sql «Operator|=» «String|R"sql(SELECT ")" FROM t»
            «String|WHERE x = ')' -- )sq"»
            «String|)sql"»«Punctuation|;»
            «Keyword|auto» wide «Operator|=» «String|LR"(w)"» «String|u8R"x(u)x"» «String|uR"(a)"» «String|UR"(b)"»«Punctuation|;»
            «Keyword|auto» notRaw «Operator|=» «Type|R» «String|"x"»«Punctuation|;»
            «Keyword|auto» bad «Operator|=» «Type|R»«String|"no space(x)no space"»«Punctuation|;»
            «Keyword|auto» open «Operator|=» «String|R"end(never closed»
            «String|still raw»
            """.trimIndent(),
        )
    }

    @Test
    fun templatesAttributesAndScopes() {
        assertGolden(
            "cpp",
            """
            #include <vector>
            #include <string_view>

            namespace app::store {
            template <typename T, class U = std::allocator<T>>
            class Store final : public Base<T> {
            public:
                [[nodiscard]] T get(std::size_t i) const noexcept { return items_[i]; }
                [[gnu::always_inline, deprecated("x")]] virtual ~Store() override = default;
                static constexpr int MAX_SIZE = 64;
                auto operator<=>(const Store&) const = default;
            private:
                std::vector<T, U> items_;
            };
            }  // namespace app::store

            int main() {
                auto lambda = [&](auto x) -> int { return x * 2; };
                std::cout << lambda(3) << std::endl;
                if (ptr == nullptr) throw std::runtime_error("null");
                return static_cast<int>(1'000'000);
            }
            """.trimIndent(),
            """
            «Punctuation|#»«Keyword|include» «String|<vector>»
            «Punctuation|#»«Keyword|include» «String|<string_view>»

            «Keyword|namespace» app«Punctuation|::»store «Punctuation|{»
            «Keyword|template» «Operator|<»«Keyword|typename» «Type|T»«Punctuation|,» «Keyword|class» «Type|U» «Operator|=» std«Punctuation|::»allocator«Operator|<»«Type|T»«Operator|>>»
            «Keyword|class» «Type|Store» «Keyword|final» «Punctuation|:» «Keyword|public» «Type|Base»«Operator|<»«Type|T»«Operator|>» «Punctuation|{»
            «Keyword|public»«Punctuation|:»
                «Punctuation|[[»«Attribute|nodiscard»«Punctuation|]]» «Type|T» «Function|get»«Punctuation|(»std«Punctuation|::»«Type|size_t» i«Punctuation|)» «Keyword|const» «Keyword|noexcept» «Punctuation|{» «Keyword|return» items_«Punctuation|[»i«Punctuation|];» «Punctuation|}»
                «Punctuation|[[»«Attribute|gnu::always_inline»«Punctuation|,» «Function|deprecated»«Punctuation|(»«String|"x"»«Punctuation|)]]» «Keyword|virtual» «Operator|~»«Function|Store»«Punctuation|()» «Keyword|override» «Operator|=» «Keyword|default»«Punctuation|;»
                «Keyword|static» «Keyword|constexpr» «Keyword|int» «Constant|MAX_SIZE» «Operator|=» «Number|64»«Punctuation|;»
                «Keyword|auto» «Keyword|operator»«Operator|<=>»«Punctuation|(»«Keyword|const» «Type|Store»«Operator|&»«Punctuation|)» «Keyword|const» «Operator|=» «Keyword|default»«Punctuation|;»
            «Keyword|private»«Punctuation|:»
                std«Punctuation|::»vector«Operator|<»«Type|T»«Punctuation|,» «Type|U»«Operator|>» items_«Punctuation|;»
            «Punctuation|};»
            «Punctuation|}»  «Comment|// namespace app::store»

            «Keyword|int» «Function|main»«Punctuation|()» «Punctuation|{»
                «Keyword|auto» lambda «Operator|=» «Punctuation|[»«Operator|&»«Punctuation|](»«Keyword|auto» x«Punctuation|)» «Operator|->» «Keyword|int» «Punctuation|{» «Keyword|return» x «Operator|*» «Number|2»«Punctuation|;» «Punctuation|};»
                std«Punctuation|::»cout «Operator|<<» «Function|lambda»«Punctuation|(»«Number|3»«Punctuation|)» «Operator|<<» std«Punctuation|::»endl«Punctuation|;»
                «Keyword|if» «Punctuation|(»ptr «Operator|==» «Constant|nullptr»«Punctuation|)» «Keyword|throw» std«Punctuation|::»«Function|runtime_error»«Punctuation|(»«String|"null"»«Punctuation|);»
                «Keyword|return» «Keyword|static_cast»«Operator|<»«Keyword|int»«Operator|>»«Punctuation|(»«Number|1'000'000»«Punctuation|);»
            «Punctuation|}»
            """.trimIndent(),
        )
    }
}
