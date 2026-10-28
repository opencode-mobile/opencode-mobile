package dev.opencode.mobile.domain.t3

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The active environment's `fileAttachments.maxUploadBytes`, keyed by profile.
 * Missing means that server accepts images only. [ActiveConnectionManager] reads
 * [maxBytes] to show the Files picker; the uploader reads it at send time.
 */
@Singleton
class T3FileAttachmentLimit
    @Inject
    constructor() {
        private val limits = ConcurrentHashMap<String, Long>()

        @Volatile
        private var activeProfileId: String? = null

        val maxBytes: Long?
            get() = activeProfileId?.let { limits[it] }

        /** Select a profile's stored limit before its descriptor is fetched again. */
        fun activate(profileId: String) {
            activeProfileId = profileId
        }

        fun note(
            profileId: String,
            maxBytes: Long?,
        ) {
            if (maxBytes == null || maxBytes < 1) {
                limits.remove(profileId)
            } else {
                limits[profileId] = maxBytes
            }
            activeProfileId = profileId
        }
    }
