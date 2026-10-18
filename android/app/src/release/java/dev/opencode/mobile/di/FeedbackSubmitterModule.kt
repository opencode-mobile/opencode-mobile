package dev.opencode.mobile.di

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.domain.feedback.FeedbackAppCheckTokenProvider
import dev.opencode.mobile.domain.feedback.FeedbackCooldownStore
import dev.opencode.mobile.domain.feedback.FeedbackFirebaseConfig
import dev.opencode.mobile.domain.feedback.FeedbackIdentityStore
import dev.opencode.mobile.domain.feedback.FeedbackSubmitter
import dev.opencode.mobile.domain.feedback.FirebaseRestFeedbackSubmitter
import dev.opencode.mobile.domain.feedback.ShareFeedbackSubmitter
import dev.opencode.mobile.platform.SigningCertificate
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Release half of the feedback submitter seam (#721): App Check from the Firebase SDK,
 * everything else over Firebase REST. A release build without google-services.json (a
 * public clone) has no FirebaseApp and shares reports instead.
 */
@Module
@InstallIn(SingletonComponent::class)
object FeedbackSubmitterModule {
    @Provides
    @Singleton
    fun provideFeedbackSubmitter(
        @ApplicationContext context: Context,
        identityStore: FeedbackIdentityStore,
        cooldownStore: FeedbackCooldownStore,
    ): FeedbackSubmitter {
        val options = FirebaseApp.getApps(context).firstOrNull()?.options ?: return ShareFeedbackSubmitter()
        val projectId = options.projectId ?: return ShareFeedbackSubmitter()
        val bucket = options.storageBucket ?: return ShareFeedbackSubmitter()
        return FirebaseRestFeedbackSubmitter(
            config =
                FeedbackFirebaseConfig(
                    apiKey = options.apiKey,
                    projectId = projectId,
                    storageBucket = bucket,
                    androidPackage = context.packageName,
                    androidCertSha1 = SigningCertificate.sha1Hex(context).orEmpty(),
                ),
            appCheck = PlayIntegrityAppCheckToken,
            identityStore = identityStore,
            cooldownStore = cooldownStore,
            httpClient =
                HttpClient(OkHttp) {
                    install(HttpTimeout) {
                        connectTimeoutMillis = 15_000
                        requestTimeoutMillis = 60_000
                    }
                },
        )
    }
}

/** The provider factory is installed by `OpenCodeReleaseObservability.initialize`. */
private object PlayIntegrityAppCheckToken : FeedbackAppCheckTokenProvider {
    override suspend fun token(): String =
        suspendCancellableCoroutine { continuation ->
            FirebaseAppCheck
                .getInstance()
                .getAppCheckToken(false)
                .addOnSuccessListener { result -> continuation.resume(result.token) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }
}
