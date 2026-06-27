package dev.opencode.mobile.markdown

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.posix.PATH_MAX
import platform.posix.getcwd

internal actual fun bundledTestResourceReader(): OpenCodeTextMateResourceReader =
    OpenCodeTextMateResourceReader { resourcePath ->
        readIosMarkdownCoreResource(resourcePath)
    }

@OptIn(ExperimentalForeignApi::class)
private fun readIosMarkdownCoreResource(resourcePath: String): String? {
    readUtf8File("$IOS_TEST_RESOURCE_ROOT/$resourcePath")?.let { return it }

    var directory = currentWorkingDirectory()
    repeat(16) {
        val current = directory ?: return@repeat
        for (candidate in resourceCandidates(current, resourcePath)) {
            val content = readUtf8File(candidate)
            if (content != null) {
                return content
            }
        }
        directory = parentDirectory(current)
    }
    return null
}

private fun resourceCandidates(root: String, resourcePath: String): List<String> =
    listOf(
        "$root/src/commonMain/resources/$resourcePath",
        "$root/build/processedResources/iosSimulatorArm64/main/$resourcePath",
        "$root/build/processedResources/iosSimulatorArm64/test/$resourcePath",
        "$root/shared/markdown-core/src/commonMain/resources/$resourcePath",
        "$root/shared/markdown-core/build/processedResources/iosSimulatorArm64/main/$resourcePath",
        "$root/shared/markdown-core/build/processedResources/iosSimulatorArm64/test/$resourcePath",
    )

@OptIn(ExperimentalForeignApi::class)
private fun currentWorkingDirectory(): String? =
    memScoped {
        val bufferSize = PATH_MAX.toInt()
        val buffer = allocArray<ByteVar>(bufferSize)
        getcwd(buffer, bufferSize.toULong())?.toKString()
    }

private fun parentDirectory(path: String): String? {
    val index = path.lastIndexOf('/')
    if (index <= 0) {
        return null
    }
    return path.substring(0, index)
}

@OptIn(ExperimentalForeignApi::class)
private fun readUtf8File(path: String): String? =
    NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null) as String?
