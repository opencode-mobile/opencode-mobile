package dev.opencode.mobile.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.opencode.mobile.domain.connection.ConnectionProfileStore
import dev.opencode.mobile.domain.connection.ConnectionSessionCoordinator
import dev.opencode.mobile.domain.connection.SecureCredentialStore

@EntryPoint
@InstallIn(SingletonComponent::class)
interface StoreScreenshotEntryPoint {
    fun connectionProfileStore(): ConnectionProfileStore

    fun secureCredentialStore(): SecureCredentialStore

    fun connectionSessionCoordinator(): ConnectionSessionCoordinator
}
