package com.npnpatidar.ncal.settings

import android.content.Context
import androidx.core.content.edit
import com.npnpatidar.ncal.logging.NcalLogger
import com.npnpatidar.ncal.tape.Grouping

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class NoteSort { DATE, NAME_ASC, NAME_DESC }

/**
 * Everything on the Settings page. Persisted in SharedPreferences
 * (`ncal_settings`), applied live — decimals/indent/grouping re-layout the
 * current tape immediately, the rest apply to the UI on the next frame.
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val decimals: Int = 5,
    val indent: Int = 2,
    val grouping: Grouping = Grouping.OFF,
    val tapeFontSp: Float = 16f,
    val keyFontSp: Float = 18f,
    val keyHeightPortDp: Float = 48f,
    val keyHeightLandDp: Float = 48f,
    val haptics: Boolean = true,
    val keySound: Boolean = true,
    val noteSort: NoteSort = NoteSort.DATE,
) {
    fun sanitized(): AppSettings = copy(
        decimals = decimals.coerceIn(0, 8),
        indent = indent.coerceIn(1, 8),
        tapeFontSp = tapeFontSp.takeIf { it.isFinite() }?.coerceIn(6f, 32f) ?: 16f,
        keyFontSp = keyFontSp.takeIf { it.isFinite() }?.coerceIn(12f, 28f) ?: 18f,
        keyHeightPortDp = keyHeightPortDp.takeIf { it.isFinite() }?.coerceIn(48f, 80f) ?: 48f,
        keyHeightLandDp = keyHeightLandDp.takeIf { it.isFinite() }?.coerceIn(48f, 64f) ?: 48f,
    )
}

class SettingsStore(private val context: Context) {

    fun load(): AppSettings {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return AppSettings(
            themeMode = enumOf<ThemeMode>(p, K_THEME, ThemeMode.SYSTEM),
            decimals = readInt(p, K_DEC, 5),
            indent = readInt(p, K_INDENT, 2),
            grouping = enumOf<Grouping>(p, K_GROUP, Grouping.OFF),
            tapeFontSp = readFloat(p, K_TAPE_FONT, 16f),
            keyFontSp = readFloat(p, K_KEY_FONT, 18f),
            keyHeightPortDp = readFloat(p, K_KEY_H_PORT, 48f),
            keyHeightLandDp = readFloat(p, K_KEY_H_LAND, 48f),
            haptics = readBoolean(p, K_HAPTIC, true),
            keySound = readBoolean(p, K_SOUND, true),
            noteSort = enumOf<NoteSort>(p, K_SORT, NoteSort.DATE),
        ).sanitized()
    }

    fun save(settings: AppSettings) {
        val s = settings.sanitized()
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
                putString(K_THEME, s.themeMode.name)
                putInt(K_DEC, s.decimals)
                putInt(K_INDENT, s.indent)
                putString(K_GROUP, s.grouping.name)
                putFloat(K_TAPE_FONT, s.tapeFontSp)
                putFloat(K_KEY_FONT, s.keyFontSp)
                putFloat(K_KEY_H_PORT, s.keyHeightPortDp)
                putFloat(K_KEY_H_LAND, s.keyHeightLandDp)
                putBoolean(K_HAPTIC, s.haptics)
                putBoolean(K_SOUND, s.keySound)
                putString(K_SORT, s.noteSort.name)
            }
        } catch (t: Throwable) {
            NcalLogger.e("Settings", "save failed", t)
        }
    }

    private fun readInt(p: android.content.SharedPreferences, key: String, fallback: Int): Int = try {
        p.getInt(key, fallback)
    } catch (_: Throwable) {
        fallback
    }

    private fun readFloat(p: android.content.SharedPreferences, key: String, fallback: Float): Float = try {
        p.getFloat(key, fallback)
    } catch (_: Throwable) {
        fallback
    }

    private fun readBoolean(p: android.content.SharedPreferences, key: String, fallback: Boolean): Boolean = try {
        p.getBoolean(key, fallback)
    } catch (_: Throwable) {
        fallback
    }

    private inline fun <reified T : Enum<T>> enumOf(
        p: android.content.SharedPreferences,
        key: String,
        fallback: T,
    ): T {
        return try {
            java.lang.Enum.valueOf(T::class.java, p.getString(key, fallback.name) ?: fallback.name)
        } catch (_: Throwable) {
            fallback
        }
    }

    companion object {
        private const val FILE = "ncal_settings"
        private const val K_THEME = "theme"
        private const val K_DEC = "decimals"
        private const val K_INDENT = "indent"
        private const val K_GROUP = "grouping"
        private const val K_TAPE_FONT = "tape_font"
        private const val K_KEY_FONT = "key_font"
        private const val K_KEY_H_PORT = "key_h_port"
        private const val K_KEY_H_LAND = "key_h_land"
        private const val K_HAPTIC = "haptics"
        private const val K_SOUND = "key_sound"
        private const val K_SORT = "note_sort"
    }
}
