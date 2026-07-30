package dev.opencode.mobile.markdown

object TextMatePackagedLanguages {
    fun primaryCanonicalNames(manifest: TextMateGrammarManifest): List<String> =
        manifest.languages
            .map { entry -> entry.language.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

    fun loadPrimaryCanonicalNames(reader: OpenCodeTextMateResourceReader): List<String> {
        val bundle = OpenCodeTextMateGrammarBundleLoader.loadBundled(reader).bundle ?: return emptyList()
        return primaryCanonicalNames(bundle.manifest)
    }
}
