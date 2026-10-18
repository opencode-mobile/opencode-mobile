package dev.opencode.mobile.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.data.local.EncryptedFeedbackIdentityStore
import dev.opencode.mobile.domain.feedback.DeviceFeedbackMetadataSource
import dev.opencode.mobile.domain.feedback.FeedbackAttachmentRenderer
import dev.opencode.mobile.domain.feedback.FeedbackCooldownStore
import dev.opencode.mobile.domain.feedback.FeedbackIdentityStore
import dev.opencode.mobile.domain.feedback.FeedbackMetadataSource
import dev.opencode.mobile.domain.feedback.SharedPreferencesFeedbackCooldownStore
import dev.opencode.mobile.ui.feedback.DeviceFeedbackAttachmentRenderer

/** In-app feedback (#721). The build-type `FeedbackSubmitterModule` binds the submitter. */
@Module
@InstallIn(SingletonComponent::class)
abstract class FeedbackModule {
    @Binds
    abstract fun bindFeedbackMetadataSource(impl: DeviceFeedbackMetadataSource): FeedbackMetadataSource

    @Binds
    abstract fun bindFeedbackCooldownStore(impl: SharedPreferencesFeedbackCooldownStore): FeedbackCooldownStore

    @Binds
    abstract fun bindFeedbackAttachmentRenderer(impl: DeviceFeedbackAttachmentRenderer): FeedbackAttachmentRenderer

    @Binds
    abstract fun bindFeedbackIdentityStore(impl: EncryptedFeedbackIdentityStore): FeedbackIdentityStore
}
