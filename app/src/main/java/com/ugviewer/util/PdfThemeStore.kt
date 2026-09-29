package com.ugviewer.util

import android.content.Context

/**
 * Persists the layout the Layout Studio saved as "how sheets should look from
 * now on": the spacing knobs of [PdfTheme] plus the body font sizes. Plain
 * floats in SharedPreferences — there is nothing here worth a database.
 */
object PdfThemeStore {

    private const val PREFS_NAME = "pdf_layout_theme"
    private const val KEY_SAVED = "saved"
    private const val KEY_GAP_ABOVE = "gapAboveChord"
    private const val KEY_GAP_BELOW = "gapBelowChord"
    private const val KEY_PLAIN_ROW = "plainRowHeight"
    private const val KEY_STANZA_GAP = "stanzaGap"
    private const val KEY_WRAP_FRACTION = "wrapFraction"
    private const val KEY_CHORD_SIZE = "chordFontSize"
    private const val KEY_LYRIC_SIZE = "lyricFontSize"

    /** The saved layout, or null when the user has never saved one. */
    data class Saved(val theme: PdfGenerator.PdfTheme, val chordSize: Float, val lyricSize: Float)

    fun load(context: Context): Saved? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_SAVED, false)) return null
            val d = PdfGenerator.PdfTheme.DEFAULT
            Saved(
                theme = PdfGenerator.PdfTheme(
                    gapAboveChord = prefs.getFloat(KEY_GAP_ABOVE, d.gapAboveChord),
                    gapBelowChord = prefs.getFloat(KEY_GAP_BELOW, d.gapBelowChord),
                    plainRowHeight = prefs.getFloat(KEY_PLAIN_ROW, d.plainRowHeight),
                    stanzaGap = prefs.getFloat(KEY_STANZA_GAP, d.stanzaGap),
                    wrapFraction = prefs.getFloat(KEY_WRAP_FRACTION, d.wrapFraction)
                ),
                chordSize = prefs.getFloat(KEY_CHORD_SIZE, PdfGenerator.DEFAULT_FONT_SIZE),
                lyricSize = prefs.getFloat(KEY_LYRIC_SIZE, PdfGenerator.DEFAULT_FONT_SIZE)
            )
        } catch (e: Exception) {
            // A broken preference must never block generating a sheet.
            null
        }
    }

    fun save(context: Context, theme: PdfGenerator.PdfTheme, chordSize: Float, lyricSize: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putBoolean(KEY_SAVED, true)
            putFloat(KEY_GAP_ABOVE, theme.gapAboveChord)
            putFloat(KEY_GAP_BELOW, theme.gapBelowChord)
            putFloat(KEY_PLAIN_ROW, theme.plainRowHeight)
            putFloat(KEY_STANZA_GAP, theme.stanzaGap)
            putFloat(KEY_WRAP_FRACTION, theme.wrapFraction)
            putFloat(KEY_CHORD_SIZE, chordSize)
            putFloat(KEY_LYRIC_SIZE, lyricSize)
            apply()
        }
    }

    /** Removes the saved layout; sheets go back to the shipped defaults. */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
