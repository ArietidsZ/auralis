package com.dialect.interpreter.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class DialectCatalogTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val catalogJson = """
        {
          "schemaVersion": "1",
          "dialects": [
            {"id": "mandarin", "displayLabel": "普通话", "shortLabel": "普", "family": "Mandarin", "asrLanguage": "Chinese", "ttsLanguageCode": "zh"},
            {"id": "cantonese", "displayLabel": "粤语", "shortLabel": "粤", "family": "Yue", "asrLanguage": "Cantonese", "ttsLanguageCode": "yue"},
            {"id": "unknownField", "displayLabel": "未知", "asrLanguage": "X"}
          ],
          "targetLanguages": [
            {"id": "en", "displayLabel": "English", "asrLanguage": "English"},
            {"id": "zh", "displayLabel": "普通话", "asrLanguage": "Chinese"}
          ]
        }
    """.trimIndent()

    @Test
    fun `catalog decodes and ignores unknown fields`() {
        val catalog = json.decodeFromString<DialectCatalog>(catalogJson)

        assertEquals("1", catalog.schemaVersion)
        assertEquals(3, catalog.dialects.size)
        assertEquals(2, catalog.targetLanguages.size)
        // Unknown fields in a dialect entry are tolerated.
        assertEquals("粤语", catalog.dialects[1].displayLabel)
    }

    @Test
    fun `defaults are applied when fields are absent`() {
        val catalog = json.decodeFromString<DialectCatalog>(
            """{"dialects":[{"id":"x","displayLabel":"X","asrLanguage":"A"}]}"""
        )

        assertEquals("1", catalog.schemaVersion)
        assertEquals("", catalog.dialects[0].shortLabel)
        assertEquals("zh", catalog.dialects[0].ttsLanguageCode)
    }

    @Test
    fun `sourceOptions maps display label to asrLanguage`() {
        val catalog = json.decodeFromString<DialectCatalog>(catalogJson)

        val options = DialectCatalogLoader.sourceOptions(catalog)
        assertEquals(listOf("普通话" to "Chinese", "粤语" to "Cantonese", "未知" to "X"), options)
    }

    @Test
    fun `targetOptions puts zh first`() {
        val catalog = json.decodeFromString<DialectCatalog>(catalogJson)

        val options = DialectCatalogLoader.targetOptions(catalog)
        assertEquals("普通话", options.first().first)
        assertEquals(listOf("普通话" to "Chinese", "English" to "English"), options)
    }
}