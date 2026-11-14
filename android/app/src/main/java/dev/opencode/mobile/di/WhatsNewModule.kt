package dev.opencode.mobile.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.domain.app.AppVersion
import dev.opencode.mobile.domain.releasenotes.ReleaseNotesParser
import dev.opencode.mobile.domain.releasenotes.ReleaseNotesPlatform
import dev.opencode.mobile.domain.releasenotes.SharedPreferencesWhatsNewStore
import dev.opencode.mobile.domain.releasenotes.WhatsNew
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WhatsNewModule {
    /** The repo-root `release-notes/release-notes.json`, bundled as an asset by Gradle. */
    private const val RELEASE_NOTES_ASSET = "release-notes.json"

    @Provides
    @Singleton
    fun provideWhatsNew(
        @ApplicationContext context: Context,
    ): WhatsNew =
        WhatsNew(
            loadReleaseNotes = {
                runCatching {
                    context.assets
                        .open(RELEASE_NOTES_ASSET)
                        .bufferedReader()
                        .use { it.readText() }
                }.map { ReleaseNotesParser.parse(it, ReleaseNotesPlatform.Android) }
                    .getOrDefault(emptyList())
            },
            store =
                SharedPreferencesWhatsNewStore(
                    context.getSharedPreferences(SharedPreferencesWhatsNewStore.PREFS_NAME, Context.MODE_PRIVATE),
                ),
            installedVersionName = AppVersion.from(context).version,
            isUpgrade = {
                // An update keeps the first install time and moves the last update time.
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                packageInfo.firstInstallTime != packageInfo.lastUpdateTime
            },
        )
}
