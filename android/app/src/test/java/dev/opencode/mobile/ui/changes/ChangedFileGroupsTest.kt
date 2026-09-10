package dev.opencode.mobile.ui.changes

import dev.opencode.mobile.domain.session.FileDiff
import dev.opencode.mobile.domain.session.FileDiffStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ChangedFileGroupsTest {
    @Test
    fun stripsSharedPrefixIntoRelativeFolders() {
        val groups =
            groupChangedFiles(
                listOf(
                    file("android/app/src/main/java/dev/opencode/mobile/ui/changes/ChangesScreen.kt"),
                    file("android/app/src/main/java/dev/opencode/mobile/ui/changes/ChangesViewModel.kt"),
                    file("android/app/src/main/java/dev/opencode/mobile/ui/sessionchat/SessionChatScreen.kt"),
                ),
            )

        assertEquals(listOf("changes", "sessionchat"), groups.map(ChangedFileGroup::directory))
        assertEquals(
            listOf("ChangesScreen.kt", "ChangesViewModel.kt"),
            groups[0].files.map { changedFileName(it.path) },
        )
        assertEquals(listOf("SessionChatScreen.kt"), groups[1].files.map { changedFileName(it.path) })
    }

    @Test
    fun keepsDotGroupForFilesInTheSharedDirectory() {
        val groups =
            groupChangedFiles(
                listOf(
                    file("android/app/src/main/java/dev/opencode/mobile/OpenCodeApplication.kt"),
                    file("android/app/src/main/java/dev/opencode/mobile/ui/changes/ChangesScreen.kt"),
                ),
            )

        assertEquals(listOf(".", "ui/changes"), groups.map(ChangedFileGroup::directory))
        assertEquals("OpenCodeApplication.kt", changedFileName(groups[0].files.single().path))
        assertEquals("ChangesScreen.kt", changedFileName(groups[1].files.single().path))
    }

    @Test
    fun usesFullFolderWhenEveryFileSharesOneParent() {
        val groups =
            groupChangedFiles(
                listOf(
                    file("Sources/App.kt"),
                    file("Sources/New.kt"),
                    file("Sources/Removed.kt"),
                ),
            )

        assertEquals(listOf("Sources"), groups.map(ChangedFileGroup::directory))
        assertEquals(listOf("App.kt", "New.kt", "Removed.kt"), groups.single().files.map { changedFileName(it.path) })
    }

    @Test
    fun groupsMixedRootsWithoutASharedPrefix() {
        val groups =
            groupChangedFiles(
                listOf(
                    file("android/Foo.kt"),
                    file("docs/list-item.md"),
                    file("ios/Bar.swift"),
                ),
            )

        assertEquals(listOf("android", "docs", "ios"), groups.map(ChangedFileGroup::directory))
    }

    @Test
    fun groupsRootLevelFiles() {
        val groups = groupChangedFiles(listOf(file("README.md"), file("LICENSE")))

        assertEquals(listOf("."), groups.map(ChangedFileGroup::directory))
        assertEquals(listOf("README.md", "LICENSE"), groups.single().files.map { changedFileName(it.path) })
    }

    @Test
    fun fileGroupsFollowTheFilter() {
        val content =
            ChangesContentState(
                files =
                    listOf(
                        file("android/app/src/main/java/dev/opencode/mobile/ui/changes/ChangesScreen.kt"),
                        file("android/app/src/main/java/dev/opencode/mobile/ui/sessionchat/SessionChatScreen.kt"),
                    ),
                filter = "changes",
            )

        assertEquals(listOf("changes"), content.fileGroups.map(ChangedFileGroup::directory))
        assertEquals("ChangesScreen.kt", changedFileName(content.fileGroups.single().files.single().path))
    }

    @Test
    fun filterKeepsUnfilteredPrefix() {
        val content =
            ChangesContentState(
                files =
                    listOf(
                        file("android/app/src/main/java/dev/opencode/mobile/OpenCodeApplication.kt"),
                        file("android/app/src/main/java/dev/opencode/mobile/ui/changes/ChangesScreen.kt"),
                    ),
                filter = "ChangesScreen",
            )

        assertEquals(listOf("ui/changes"), content.fileGroups.map(ChangedFileGroup::directory))
    }

    @Test
    fun emptyFilesYieldNoGroups() {
        assertEquals(emptyList<ChangedFileGroup>(), groupChangedFiles(emptyList()))
    }

    private fun file(path: String) = FileDiff(path, null, 1, 0, FileDiffStatus.Modified)
}
