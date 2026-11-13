package dev.opencode.hexcode.home

import android.content.res.AssetManager

/**
 * Debug builds: the `artifact` sample session's artifact (`TodaySessionDemo.Artifact`), answered from the app's own
 * assets instead of a server, so the artifact viewer opens with no computer. The page is a copy of
 * `hexcode/store-listing/sample-artifact.html` in the debug source set's assets; the artifact has no other files, so
 * anything else under it is a `404`. Real artifacts never come here.
 */
class SampleArtifactFetcher(
    private val assets: AssetManager,
    private val id: String,
) : ArtifactFetcher {
    override suspend fun fetch(path: String): ArtifactFile {
        // `api/experimental/artifacts/<id>/view/<rest>`: the page is the artifact's entry, at `view/` itself.
        val rest = path.substringAfter("/$id/view/", missingDelimiterValue = "-").substringBefore('?')
        if (rest != "" && rest != ENTRY) return missing()
        val page = runCatching { assets.open(ASSET).use { it.readBytes() } }.getOrNull() ?: return missing()
        return ArtifactFile(200, mapOf("content-type" to "text/html; charset=utf-8"), page)
    }

    private fun missing() = ArtifactFile(404, emptyMap(), ByteArray(0))

    companion object {
        /** Where the web view thinks the sample artifact is: never fetched, every request under it is answered here. */
        const val ADDRESS = "http://192.168.1.2:4096"

        private const val ASSET = "sample-artifact.html"
        private const val ENTRY = "index.html"
    }
}
