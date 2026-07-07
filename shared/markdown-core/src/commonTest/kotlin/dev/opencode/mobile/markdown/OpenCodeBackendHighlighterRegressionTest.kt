package dev.opencode.mobile.markdown

import kotlin.test.Test
import kotlin.test.assertTrue

class OpenCodeBackendHighlighterRegressionTest {
    @Test
    fun kotlinRegexBackendCoversWithCacheIdentifiersUniformly() {
        val code = withCacheSnippet()
        val result = highlighter(KotlinRegexTextMateRegexBackend).highlight("javascript", code)
        HighlighterRegressionAssertions.assertIdentifiersUniformlyCovered(
            code = code,
            spans = result.spans,
            identifiers = listOf("withCache", "client", "options", "maxSize", "Map"),
            label = "kotlin-regex",
        )
    }

    @Test
    fun iosHybridBackendCoversWithCacheIdentifiersUniformly() {
        val code = withCacheSnippet()
        val result = highlighter(OpenCodeKotlinRegexHybridTextMateBackend).highlight("javascript", code)
        HighlighterRegressionAssertions.assertIdentifiersUniformlyCovered(
            code = code,
            spans = result.spans,
            identifiers = listOf("withCache", "client", "options", "maxSize", "Map"),
            label = "ios-hybrid",
        )
    }

    @Test
    fun iosHybridBackendNormalizesHttpClientPrivateFields() {
        val fixture =
            HighlighterGoldenFixtures.all.single { it.id == "javascript-http-client-class-repro" }
        val result = highlighter(OpenCodeKotlinRegexHybridTextMateBackend).highlight(fixture.language, fixture.code)
        val privateFields = listOf("#baseUrl", "#headers", "#interceptors", "#timeout")
        HighlighterRegressionAssertions.assertPrivateFieldsMatchWebRoles(
            code = fixture.code,
            spans = result.spans,
            privateFields = privateFields,
            label = "ios-hybrid",
        )
        OpenCodeMarkdownHighlighterSpanInvariantTest.assertNoOverlappingSpanRanges(fixture.id, result.spans)
    }

    @Test
    fun iosHybridBackendEmitsBackendFallbackDiagnostic() {
        val result = highlighter(OpenCodeKotlinRegexHybridTextMateBackend).highlight("javascript", "const answer = 1")
        assertTrue(
            result.diagnostics.any { it.kind == MarkdownDiagnosticKind.HighlighterBackendFallback },
            "Expected ios hybrid backend fallback diagnostic, got ${result.diagnostics}",
        )
    }

    @Test
    fun iosHybridBackendCoversJavaMainClassRegressionFixture() {
        val fixture = HighlighterGoldenFixtures.all.single { it.id == "java-basic" }
        val result = highlighter(OpenCodeKotlinRegexHybridTextMateBackend).highlight(fixture.language, fixture.code)
        HighlighterRegressionAssertions.assertTokenRoles(
            code = fixture.code,
            spans = result.spans,
            expectations =
                listOf(
                    "public" to CodeTokenRole.Keyword,
                    "Main" to CodeTokenRole.Type,
                    "println" to CodeTokenRole.Function,
                    "1" to CodeTokenRole.Number,
                ),
            label = "ios-hybrid-java-basic",
        )
        assertTrue(
            result.spans.any { span ->
                span.role == CodeTokenRole.String &&
                    fixture.code.substring(span.start, span.end).contains("Division by zero")
            },
            "Expected catch string literal in ${result.spans}",
        )
        OpenCodeMarkdownHighlighterSpanInvariantTest.assertNoOverlappingSpanRanges(fixture.id, result.spans)
    }

    @Test
    fun kotlinRegexBackendRendersLargeCppPopularitySnippet() {
        val code = largeCppPopularitySnippet()
        val result = highlighter(KotlinRegexTextMateRegexBackend).highlight("cpp", code)

        assertTrue(result.spans.isNotEmpty(), "Expected C++ spans for popularity snippet")
        val roles = result.spans.map { it.role }.toSet()
        assertTrue(
            listOf(
                CodeTokenRole.Comment,
                CodeTokenRole.Keyword,
                CodeTokenRole.Type,
                CodeTokenRole.String,
                CodeTokenRole.Function,
                CodeTokenRole.Number,
            ).all { role -> role in roles },
            "Expected visibly colorable C++ roles, got ${roles.sortedBy { it.name }}",
        )
        HighlighterRegressionAssertions.assertTokenRoles(
            code = code,
            spans = result.spans,
            expectations =
                listOf(
                    "return" to CodeTokenRole.Keyword,
                    "\"Alice\"" to CodeTokenRole.String,
                    "greet" to CodeTokenRole.Function,
                    "30" to CodeTokenRole.Number,
                ),
            label = "cpp-popularity",
        )
        OpenCodeMarkdownHighlighterSpanInvariantTest.assertNoDuplicateSpanRanges("cpp-popularity", result.spans)
        OpenCodeMarkdownHighlighterSpanInvariantTest.assertNoOverlappingSpanRanges("cpp-popularity", result.spans)
    }

    @Test
    fun kotlinRegexBackendRendersPhpPopularitySnippet() {
        val code = phpPopularitySnippet()
        val result = highlighter(KotlinRegexTextMateRegexBackend).highlight("php", code)

        assertTrue(result.spans.isNotEmpty(), "Expected PHP spans for popularity snippet")
        val roles = result.spans.map { it.role }.toSet()
        assertTrue(
            listOf(
                CodeTokenRole.Comment,
                CodeTokenRole.Keyword,
                CodeTokenRole.Type,
                CodeTokenRole.String,
                CodeTokenRole.Function,
                CodeTokenRole.Number,
                CodeTokenRole.Variable,
                CodeTokenRole.Operator,
                CodeTokenRole.Constant,
            ).all { role -> role in roles },
            "Expected visibly colorable PHP roles, got ${roles.sortedBy { it.name }}",
        )
        HighlighterRegressionAssertions.assertTokenRoles(
            code = code,
            spans = result.spans,
            expectations =
                listOf(
                    "function" to CodeTokenRole.Keyword,
                    "string" to CodeTokenRole.Type,
                    "\$name" to CodeTokenRole.Variable,
                    "\"Alice\"" to CodeTokenRole.String,
                    "greet" to CodeTokenRole.Function,
                    "30" to CodeTokenRole.Number,
                    "PHP_EOL" to CodeTokenRole.Constant,
                    "->" to CodeTokenRole.Operator,
                ),
            label = "php-popularity",
        )
        OpenCodeMarkdownHighlighterSpanInvariantTest.assertNoDuplicateSpanRanges("php-popularity", result.spans)
        OpenCodeMarkdownHighlighterSpanInvariantTest.assertNoOverlappingSpanRanges("php-popularity", result.spans)
    }

    private fun withCacheSnippet(): String =
        """
        // Cache decorator for GET requests
        function withCache(client, options = {
          const { ttl = 60000, maxSize = 100 }
          const cache = new Map();
        """.trimIndent()

    private fun largeCppPopularitySnippet(): String =
        """
        // C++ - The #5 Most Popular Language

        #include <iostream>
        #include <vector>
        #include <string>
        #include <algorithm>
        #include <numeric>

        // Function
        std::string greet(const std::string& personName) {
            return "Hello, " + personName + "!";
        }

        // Class
        class Student {
        private:
            std::string name;
            int age;

        public:
            Student(std::string n, int a) : name(std::move(n)), age(a) {}

            std::string introduce() const {
                return "I'm " + name + ", " + std::to_string(age) + " years old";
            }

            std::string getName() const { return name; }
            int getAge() const { return age; }
        };

        int main() {
            // Variables and data types
            std::string name = "Alice";
            int age = 30;
            bool isStudent = true;
            std::vector<int> grades = {85, 92, 78, 95};

            // Method call
            std::cout << greet(name) << std::endl;

            // Object creation
            Student student(name, age);
            std::cout << student.introduce() << std::endl;

            // Lambda and algorithm
            std::vector<int> passedGrades;
            std::copy_if(grades.begin(), grades.end(),
                         std::back_inserter(passedGrades),
                         [](int g) { return g >= 80; });

            double average = std::accumulate(grades.begin(), grades.end(), 0.0) / grades.size();

            std::cout << "Passed grades: ";
            for (int g : passedGrades) std::cout << g << " ";
            std::cout << std::endl;

            std::cout << "Average: " << average << std::endl;

            return 0;
        }
        """.trimIndent()

    private fun phpPopularitySnippet(): String =
        """
        <?php
        // PHP - The #7 Most Popular Language

        // Variables
        ${'$'}name = "Alice";
        ${'$'}age = 30;
        ${'$'}isStudent = true;
        ${'$'}grades = [85, 92, 78, 95];

        // Function
        function greet(string ${'$'}personName): string {
            return "Hello, ${'$'}personName!";
        }

        // Class
        class Student {
            private string ${'$'}name;
            private int ${'$'}age;
            private array ${'$'}grades;

            public function __construct(string ${'$'}name, int ${'$'}age, array ${'$'}grades) {
                ${'$'}this->name = ${'$'}name;
                ${'$'}this->age = ${'$'}age;
                ${'$'}this->grades = ${'$'}grades;
            }
        }

        echo greet(${'$'}name) . PHP_EOL;
        ${'$'}student = new Student(${'$'}name, ${'$'}age, ${'$'}grades);
        echo ${'$'}student->introduce() . PHP_EOL;
        ?>
        """.trimIndent()

    private fun highlighter(backend: OpenCodeTextMateRegexBackend): OpenCodeSyntaxHighlighter =
        OpenCodeSyntaxHighlighter(
            grammarBundle = bundledGrammarBundle(),
            textMateTokenizer = OpenCodeTextMateNativeTokenizer(backend),
        )
}
