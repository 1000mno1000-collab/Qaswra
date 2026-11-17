package com.example.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.util.Locale

enum class AppLanguage(
    val tag: String,
    val nativeName: String,
    val shortBadge: String,
    val isRtl: Boolean
) {
    ARABIC(tag = "ar", nativeName = "العربية", shortBadge = "عربي", isRtl = true),
    ENGLISH(tag = "en", nativeName = "English", shortBadge = "EN", isRtl = false),
    PERSIAN(tag = "fa", nativeName = "فارسی", shortBadge = "فارسى", isRtl = true);

    companion object {
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) } ?: ARABIC
    }
}

private val Context.voiceProDataStore: DataStore<Preferences> by preferencesDataStore(name = "voice_pro_settings")

private class LocalizedContextWrapper(
    base: Context,
    private val localizedResourcesContext: Context
) : ContextWrapper(base) {
    override fun getResources(): Resources = localizedResourcesContext.resources
    override fun getAssets(): AssetManager = localizedResourcesContext.assets
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

object LocaleHelper {
    private const val PREFS_NAME = "voice_pro_prefs"
    private const val KEY_PREFS_LANG = "app_language"
    private val KEY_DATASTORE_LANG = stringPreferencesKey("app_language")

    fun observeLanguage(context: Context): Flow<AppLanguage> =
        context.applicationContext.voiceProDataStore.data
            .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
            .map { prefs ->
                val stored = prefs[KEY_DATASTORE_LANG] ?: getSavedLanguage(context).tag
                AppLanguage.fromTag(stored)
            }

    suspend fun saveLanguageToDataStore(context: Context, language: AppLanguage) {
        saveLanguage(context, language)
        runCatching {
            context.applicationContext.voiceProDataStore.edit { prefs ->
                prefs[KEY_DATASTORE_LANG] = language.tag
            }
        }
    }

    fun getSavedLanguage(context: Context): AppLanguage {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AppLanguage.fromTag(prefs.getString(KEY_PREFS_LANG, AppLanguage.ARABIC.tag))
    }

    fun saveLanguage(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFS_LANG, language.tag)
            .apply()
    }

    fun createLocalizedContext(base: Context, language: AppLanguage): Context {
        val locale = Locale.forLanguageTag(language.tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        val configContext = base.createConfigurationContext(config)
        return LocalizedContextWrapper(base, configContext)
    }
}
