package dev.opencode.mobile.network

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.parameter

fun HttpRequestBuilder.applyDirectory(directory: String?) {
  directory?.let { parameter("directory", it) }
}
