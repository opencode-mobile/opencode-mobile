package dev.opencode.mobile.domain.json

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder

/**
 * JSON for prompts, messages, and other server payloads.
 *
 * Decode failures omit the input. Diagnostic logs and Crashlytics keep the
 * error path, not the body.
 */
@OptIn(ExperimentalSerializationApi::class)
fun serverPayloadJson(builder: JsonBuilder.() -> Unit = {}): Json =
    Json {
        ignoreUnknownKeys = true
        exceptionsWithDebugInfo = false
        builder()
    }
