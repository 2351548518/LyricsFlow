package com.example.lyricsflow.core

/** Only inputs that affect this annotation request participate in cancellation/deduplication. */
internal data class AnnotationRequestKey(
    val track: String?, val source: Provider?, val lyricsHash: String?,
    val enabled: Boolean, val missing: Boolean, val url: String, val model: String,
)

internal fun ScreenState.annotationRequestKey(romanization: Boolean): AnnotationRequestKey =
    AnnotationRequestKey(playback.track?.key, lyrics?.source, lyrics?.hash,
        prefs.consent && !demo && if (romanization) prefs.romanization else prefs.autoTranslate,
        if (romanization) lyrics?.needsWordAlignment == true else lyrics?.needsTranslation == true,
        prefs.translationUrl, prefs.model)
