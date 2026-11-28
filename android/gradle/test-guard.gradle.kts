// Runs a module's unit tests under the :test-guard isolation guard. Apply it from the module's
// build file next to `testImplementation(project(":test-guard"))`.
//
// JUnit 4 tests run on the JUnit Platform through the vintage engine, so the guard's listener sees
// every test. A listener can't fail a test, so it writes what it caught to reportDir and the task
// fails here, after the tests, naming each test and the host or state involved.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    val reportDir = layout.buildDirectory.dir("test-guard/$name").get().asFile
    systemProperty("opencode.testguard.reportDir", reportDir.absolutePath)
    doFirst { reportDir.deleteRecursively() }
    doLast {
        val violations =
            reportDir
                .listFiles()
                .orEmpty()
                .sortedBy { it.name }
                .flatMap { it.readLines() }
                .filter { it.isNotBlank() }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Unit tests broke test isolation (see android/test-guard):\n" + violations.joinToString("\n") { "  $it" },
            )
        }
    }
}
