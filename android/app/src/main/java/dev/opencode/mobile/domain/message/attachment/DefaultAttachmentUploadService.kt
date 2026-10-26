package dev.opencode.mobile.domain.message

import dev.opencode.mobile.data.remote.AttachmentUploadApi
import dev.opencode.mobile.domain.diagnostic.DiagnosticLog
import dev.opencode.mobile.domain.diagnostic.DiagnosticLogArea
import dev.opencode.mobile.network.ActiveConnectionManager
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultAttachmentUploadService
    @Inject
    constructor(
        private val api: AttachmentUploadApi,
        private val activeConnectionManager: ActiveConnectionManager,
    ) : AttachmentUploadService {
        override fun isAvailable(): Boolean = activeConnectionManager.currentCapabilities().supportsAttachmentUpload

        override suspend fun upload(
            file: File,
            filename: String,
            directory: String,
            onProgress: (sent: Long, total: Long) -> Unit,
        ): AttachmentUploadResult {
            val tmp = activeConnectionManager.attachmentUploadTmpDirectory() ?: return AttachmentUploadResult.ContractMiss
            val serverPath = UploadedAttachments.serverPath(tmp, UUID.randomUUID().toString(), filename)
            val response =
                try {
                    api.write(serverPath, file, directory, onProgress)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    DiagnosticLog.warning(DiagnosticLogArea.Chat, "attachment upload failed", error)
                    return AttachmentUploadResult.TransportFailure
                }
            val result =
                when {
                    !response.isSuccess -> AttachmentUploadFailure.classify(response.status)
                    response.path != null -> AttachmentUploadResult.Uploaded(response.path)
                    // A 200 without data.path is a reshaped route, not a failed file.
                    else -> AttachmentUploadResult.ContractMiss
                }
            DiagnosticLog.info(DiagnosticLogArea.Chat, "attachment upload status=${response.status} result=${result::class.simpleName}")
            return result
        }

        override fun onContractMiss() {
            DiagnosticLog.warning(DiagnosticLogArea.Chat, "attachment upload contract miss; using data URLs")
            activeConnectionManager.disableAttachmentUpload()
        }
    }
