package dev.opencode.mobile.markdown

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class OpenCodeBundledJoniMarkdownHighlighterAndroidTest {
    @BeforeTest
    fun admitPackagedLanguagesForBundledJoniCoverage() {
        TextMateLanguageGateRegistry.update(TextMateLanguageGate { _, _ -> true })
    }

    @AfterTest
    fun resetLanguageGate() {
        TextMateLanguageGateRegistry.resetForTests()
    }

    @Test
    fun bundledJoniHighlighterUsesTextMateNotSemanticFallback() {
        val fixture =
            HighlighterGoldenFixtures.all.single { it.id == "javascript-http-client-class-repro" }
        val highlighter =
            OpenCodeBundledJoniMarkdownHighlighter.create(
                OpenCodeTextMateResourceReader { resourcePath ->
                    javaClass.classLoader?.getResource(resourcePath)?.readText()
                },
            )
        val result = highlighter.highlight(fixture.language, fixture.code)

        assertTrue(
            result.diagnostics.none { it.kind == MarkdownDiagnosticKind.HighlighterFallback },
            "Bundled Joni highlighter should not use semantic fallback, got ${result.diagnostics}",
        )
        assertTrue(
            result.diagnostics.none { it.kind == MarkdownDiagnosticKind.HighlighterBackendFallback },
            "Bundled Joni highlighter should not report backend fallback, got ${result.diagnostics}",
        )
        HighlighterRegressionAssertions.assertPrivateFieldsMatchWebRoles(
            code = fixture.code,
            spans = result.spans,
            privateFields = listOf("#baseUrl", "#headers", "#interceptors", "#timeout"),
            label = "android-joni",
        )
    }

    @Test
    fun bundledJoniHighlighterRendersLargeCppPopularitySnippet() {
        val highlighter =
            OpenCodeBundledJoniMarkdownHighlighter.create(
                OpenCodeTextMateResourceReader { resourcePath ->
                    javaClass.classLoader?.getResource(resourcePath)?.readText()
                },
            )
        val document =
            OpenCodeMarkdownParser.parse(
                """
                ```cpp
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
                ```
                """.trimIndent(),
                codeHighlighter = highlighter,
            )

        assertTrue(document.diagnostics.isEmpty(), "Expected no parser diagnostics, got ${document.diagnostics}")
        val block = document.blocks.single() as MarkdownBlock.CodeBlock
        assertTrue(block.spans.isNotEmpty(), "Expected C++ spans for popularity snippet")
        val roles = block.spans.map { it.role }.toSet()
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
        block.assertTextHasRole("// C++ - The #5 Most Popular Language", CodeTokenRole.Comment)
        block.assertTextHasRole("return", CodeTokenRole.Keyword)
        block.assertTextHasRole("\"Alice\"", CodeTokenRole.String)
        block.assertTextHasRole("greet", CodeTokenRole.Function, after = "std::string ")
        block.assertTextHasRole("30", CodeTokenRole.Number)
        OpenCodeMarkdownHighlighterSpanInvariantTest.assertNoDuplicateSpanRanges("cpp-popularity-joni", block.spans)
        OpenCodeMarkdownHighlighterSpanInvariantTest.assertNoOverlappingSpanRanges("cpp-popularity-joni", block.spans)
    }

    @Test
    fun bundledJoniHighlighterRendersLargePhpPopularitySnippet() {
        val highlighter =
            OpenCodeBundledJoniMarkdownHighlighter.create(
                OpenCodeTextMateResourceReader { resourcePath ->
                    javaClass.classLoader?.getResource(resourcePath)?.readText()
                },
            )
        val document =
            OpenCodeMarkdownParser.parse(
                """
                ```php
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

                    public function introduce(): string {
                        return "I'm {${'$'}this->name}, {${'$'}this->age} years old";
                    }

                    public function getPassedGrades(): array {
                        return array_filter(${'$'}this->grades, fn(${'$'}g) => ${'$'}g >= 80);
                    }

                    public function getName(): string {
                        return ${'$'}this->name;
                    }
                }

                // Main execution
                echo greet(${'$'}name) . PHP_EOL;

                ${'$'}student = new Student(${'$'}name, ${'$'}age, ${'$'}grades);
                echo ${'$'}student->introduce() . PHP_EOL;

                ${'$'}passedGrades = ${'$'}student->getPassedGrades();
                echo "Passed grades: " . implode(", ", ${'$'}passedGrades) . PHP_EOL;

                ${'$'}average = array_sum(${'$'}grades) / count(${'$'}grades);
                echo "Average: ${'$'}average" . PHP_EOL;

                // Array functions
                ${'$'}filtered = array_filter(${'$'}grades, fn(${'$'}g) => ${'$'}g >= 80);
                ${'$'}mapped = array_map(fn(${'$'}g) => "Grade: ${'$'}g", ${'$'}grades);

                // Null coalescing and type hints
                function getStudentName(?Student ${'$'}student): string {
                    return ${'$'}student?->getName() ?? "Unknown";
                }

                echo getStudentName(${'$'}student) . PHP_EOL;
                ?>
                ```
                """.trimIndent(),
                codeHighlighter = highlighter,
            )

        assertTrue(document.diagnostics.isEmpty(), "Expected no parser diagnostics, got ${document.diagnostics}")
        val block = document.blocks.single() as MarkdownBlock.CodeBlock
        assertTrue(block.spans.isNotEmpty(), "Expected PHP spans for popularity snippet")
        val roles = block.spans.map { it.role }.toSet()
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
        block.assertTextHasRole("// PHP - The #7 Most Popular Language", CodeTokenRole.Comment)
        block.assertTextHasRole("function", CodeTokenRole.Keyword)
        block.assertTextHasRole("string", CodeTokenRole.Type)
        block.assertTextHasRole("${'$'}name", CodeTokenRole.Variable)
        block.assertTextHasRole("\"Alice\"", CodeTokenRole.String)
        block.assertTextHasRole("greet", CodeTokenRole.Function)
        block.assertTextHasRole("30", CodeTokenRole.Number)
        block.assertTextHasRole("PHP_EOL", CodeTokenRole.Constant)
        block.assertTextHasRole("->", CodeTokenRole.Operator)
        OpenCodeMarkdownHighlighterSpanInvariantTest.assertNoDuplicateSpanRanges("php-popularity-joni", block.spans)
        OpenCodeMarkdownHighlighterSpanInvariantTest.assertNoOverlappingSpanRanges("php-popularity-joni", block.spans)
    }

    private fun MarkdownBlock.CodeBlock.assertTextHasRole(
        text: String,
        role: CodeTokenRole,
        after: String? = null,
    ) {
        val searchStart = after?.let { code.indexOf(it).takeIf { index -> index >= 0 }?.plus(it.length) } ?: 0
        val start = code.indexOf(text, startIndex = searchStart)
        assertTrue(start >= 0, "Expected to find '$text' in C++ snippet")
        val end = start + text.length
        assertTrue(
            spans.any { span -> span.role == role && span.start < end && span.end > start },
            "Expected '$text' to include role $role, got ${
                spans.filter { span -> span.start < end && span.end > start }
            }",
        )
    }
}
