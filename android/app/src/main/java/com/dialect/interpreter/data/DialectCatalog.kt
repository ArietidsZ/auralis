package com.dialect.interpreter.data

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Canonical dialect/language catalog, loaded from the bundled app asset that is
 * generated from `shared/dialect-catalog/catalog.json` (the single source of
 * truth). Replaces the previously hardcoded dialect/language lists in the UI.
 */
@Serializable
data class DialectCatalog(
    @SerialName("schemaVersion") val schemaVersion: String = "1",
    @SerialName("dialects") val dialects: List<DialectEntry> = emptyList(),
    @SerialName("targetLanguages") val targetLanguages: List<LanguageEntry> = emptyList()
)

@Serializable
data class DialectEntry(
    @SerialName("id") val id: String,
    @SerialName("displayLabel") val displayLabel: String,
    @SerialName("shortLabel") val shortLabel: String = "",
    @SerialName("family") val family: String = "",
    @SerialName("asrLanguage") val asrLanguage: String,
    @SerialName("ttsLanguageCode") val ttsLanguageCode: String = "zh"
)

@Serializable
data class LanguageEntry(
    @SerialName("id") val id: String,
    @SerialName("displayLabel") val displayLabel: String,
    @SerialName("asrLanguage") val asrLanguage: String
)

/**
 * Loads the canonical dialect catalog from the app's generated assets.
 */
class DialectCatalogLoader(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    fun load(): DialectCatalog {
        val raw = context.assets.open("dialect/catalog.json").bufferedReader().use { it.readText() }
        return json.decodeFromString(raw)
    }

    companion object {
        /** Source-option pairs for the source dialect selector: (displayLabel -> asrLanguage). */
        fun sourceOptions(catalog: DialectCatalog): List<Pair<String, String>> =
            catalog.dialects.map { it.displayLabel to it.asrLanguage }

        /** Target-language options: (displayLabel -> asrLanguage). Mandarin/preferred target first. */
        fun targetOptions(catalog: DialectCatalog): List<Pair<String, String>> {
            val rest = catalog.targetLanguages.filter { it.id != "zh" }
            val zh = catalog.targetLanguages.firstOrNull { it.id == "zh" }
            val reordered = listOfNotNull(zh) + rest
            return reordered.map { it.displayLabel to it.asrLanguage }
        }
    }
}