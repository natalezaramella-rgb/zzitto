package com.zaramella.zzitto.ui.licenses

import android.content.res.AssetManager
import java.io.FileNotFoundException

/**
 * Testo delle licenze open source (Spec §2.2) letto da THIRD_PARTY_LICENSES.md, che il build copia
 * negli asset generati. Parser Markdown minimo: titoli, elenchi puntati, paragrafi.
 * Aggiunto il 22/10/2026 alle 11:59.
 */
sealed interface LicenseBlock {
    data class Heading(val level: Int, val text: String) : LicenseBlock
    data class Bullet(val text: String) : LicenseBlock
    data class Paragraph(val text: String) : LicenseBlock
}

object LicensesText {
    const val ASSET_NAME = "THIRD_PARTY_LICENSES.md"

    /** Restituisce null se l'asset non è presente in questa build. */
    fun load(assets: AssetManager): String? = try {
        assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (_: FileNotFoundException) {
        null
    }

    fun parse(markdown: String): List<LicenseBlock> {
        val blocks = mutableListOf<LicenseBlock>()
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotEmpty()) {
                blocks += LicenseBlock.Paragraph(paragraph.toString())
                paragraph.clear()
            }
        }
        markdown.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            val trimmed = line.trimStart()
            when {
                trimmed.isEmpty() -> flush()
                trimmed.startsWith("#") -> {
                    flush()
                    val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(6)
                    blocks += LicenseBlock.Heading(level, clean(trimmed.drop(level).trim()))
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    flush()
                    blocks += LicenseBlock.Bullet(clean(trimmed.drop(2).trim()))
                }
                trimmed.startsWith("|") && trimmed.replace("|", "").replace("-", "").replace(":", "").isBlank() -> Unit // separatore di tabella
                trimmed.startsWith("|") -> {
                    flush()
                    val cells = trimmed.trim('|').split('|').map { clean(it.trim()) }.filter { it.isNotEmpty() }
                    blocks += LicenseBlock.Bullet(cells.joinToString(" · "))
                }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(clean(trimmed))
                }
            }
        }
        flush()
        return blocks
    }

    /** Toglie enfasi e link Markdown lasciando il testo e l'indirizzo. */
    private fun clean(text: String): String = text
        .replace(Regex("\\[([^\\]]+)]\\(([^)]+)\\)"), "$1 ($2)")
        .replace("**", "")
        .replace("`", "")
        .replace(Regex("(?<![\\w*])\\*(?!\\s)([^*]+)(?<!\\s)\\*(?![\\w*])"), "$1")
}
