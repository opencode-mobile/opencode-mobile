package dev.opencode.mobile.ui.componentgallery.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

class RenderedMarkdownGalleryViewModel : ViewModel() {
    var selectedSampleId by mutableStateOf(RenderedMarkdownGalleryMocks.samples.first().id)
        private set

    val selectedSample: RenderedMarkdownGalleryMock
        get() =
            RenderedMarkdownGalleryMocks.samples.firstOrNull { it.id == selectedSampleId }
                ?: RenderedMarkdownGalleryMocks.samples.first()

    fun selectSample(id: String) {
        selectedSampleId = id
    }
}
