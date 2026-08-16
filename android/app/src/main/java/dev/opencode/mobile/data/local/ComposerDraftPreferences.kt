package dev.opencode.mobile.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Context.composerDraftDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "composer_draft")
