package dev.opencode.mobile.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.domain.feedback.FeedbackSubmitter
import dev.opencode.mobile.domain.feedback.ShareFeedbackSubmitter
import javax.inject.Singleton

/**
 * Debug half of the feedback submitter seam (#721). Debug builds stay Firebase-free:
 * every report goes to the share sheet. The release source set has the Firebase REST one.
 */
@Module
@InstallIn(SingletonComponent::class)
object FeedbackSubmitterModule {
    @Provides
    @Singleton
    fun provideFeedbackSubmitter(): FeedbackSubmitter = ShareFeedbackSubmitter()
}
