package com.rjnsdev.linklift.app

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

const val linkLiftUserAgent =
    "Mozilla/5.0 (Linux; Android 15; LinkLift) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

internal val Context.linkLiftDataStore by preferencesDataStore(name = "linklift_preferences")

internal object PreferenceKeys {
    val wifiOnly = booleanPreferencesKey("wifi_only")
    val completionNotifications = booleanPreferencesKey("completion_notifications")
    val preferredQuality = stringPreferencesKey("preferred_quality")
    val downloadIds = stringSetPreferencesKey("download_ids")
    val downloadSourceUrls = stringPreferencesKey("download_source_urls")
}
