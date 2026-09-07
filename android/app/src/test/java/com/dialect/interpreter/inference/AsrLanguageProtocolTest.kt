package com.dialect.interpreter.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Qwen3-ASR canonical language protocol (upstream concatenates the hint
 * verbatim: `Encode("language " + name)`). Only full English names are
 * canonical; ISO codes would inject out-of-distribution prompt tokens.
 */
class AsrLanguageProtocolTest {

    @Test
    fun canonicalNames_matchUpstreamQwen3Protocol() {
        // From the Qwen3-ASR model card / sherpa python example ("Korean,
        // Chinese, English") — full names, exact case.
        assertEquals("Chinese", LanguageCodes.canonicalAsrLanguage("Chinese"))
        assertEquals("English", LanguageCodes.canonicalAsrLanguage("english"))
        assertEquals("German", LanguageCodes.canonicalAsrLanguage("German"))
        assertEquals("Cantonese", LanguageCodes.canonicalAsrLanguage("粤语"))
        assertEquals("Japanese", LanguageCodes.canonicalAsrLanguage("日本語"))
        assertEquals("Japanese", LanguageCodes.canonicalAsrLanguage("ja"))
        assertEquals("Korean", LanguageCodes.canonicalAsrLanguage("ko"))
        assertEquals("French", LanguageCodes.canonicalAsrLanguage("fr"))
        assertEquals("Russian", LanguageCodes.canonicalAsrLanguage("ru"))
    }

    @Test
    fun unmappableHints_areDropped_notInjectedIntoPrompt() {
        assertNull(LanguageCodes.canonicalAsrLanguage("klingon"))
        assertNull(LanguageCodes.canonicalAsrLanguage("zh-CN")) // ISO variants are not canonical
        assertNull(LanguageCodes.canonicalAsrLanguage(""))
        assertNull(LanguageCodes.canonicalAsrLanguage(null))
        assertNull(LanguageCodes.canonicalAsrLanguage("  "))
    }

    @Test
    fun pipelineAutoHint_isNotAForcedCanonicalName() {
        // PipelineOrchestrator passes "auto": it must drop (auto-detect),
        // never map to a canonical name that would bias the prompt.
        assertNull(LanguageCodes.canonicalAsrLanguage("auto"))
        assertNull(LanguageCodes.canonicalAsrLanguage("AUTO"))
    }

    @Test
    fun ttsNormalize_contractUnchanged() {
        // TTS lane still resolves its own ids through normalize() — do not change.
        assertEquals("zh", LanguageCodes.normalize("中文"))
        assertEquals("en", LanguageCodes.normalize("English"))
        assertNull(LanguageCodes.normalize("klingon"))
    }
}
