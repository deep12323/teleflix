package com.teleflix.app

import java.text.Normalizer
import java.util.regex.Pattern

/**
 * Ported from Telegram-stremio's TelegramSearchMatcher.
 * Provides query building, matching, and scoring logic for Telegram search results.
 */
object TelegramSearchMatcher {
    const val SCORE_THRESHOLD = 55

    private const val SEP = """[\s._\-x+,&:]{0,2}"""
    private const val SEP_MID = """[\s._\-x+,&:]{0,4}"""

    // English/Hebrew + Spanish T01C01 patterns
    private val EPISODE_PATTERN = Pattern.compile(
        """[Ss][e]?(?:ason)?${SEP}(\d{1,2})${SEP_MID}[Ee][p]?(?:isode)?${SEP}(\d{1,4})""" +
        """|(?<!\d)(\d{1,2})[xX](\d{1,4})(?!\d)""" +
        """|ע(?:ונה)?${SEP}(\d{1,2})${SEP_MID}פ(?:רק)?${SEP}(\d{1,4})""" +
        """|[Tt](?:emporada)?${SEP}(\d{1,2})${SEP_MID}[Cc](?:apitulo|apítulo)?${SEP}(\d{1,4})""",
        Pattern.CASE_INSENSITIVE
    )

    private val EPISODE_ONLY_PATTERN = Pattern.compile(
        """פ(?:רק)?${SEP}(\d{1,4})""" +
        """|[Ee][p]?(?:isode)?${SEP}(\d{1,4})""" +
        """|[Cc]ap(?:itulo|ítulo)?${SEP}(\d{1,4})""",
        Pattern.CASE_INSENSITIVE
    )

    private val YEAR_PATTERN = Pattern.compile("""\b(?:19|20)\d{2}\b""")
    private val NOISE = Pattern.compile("""[._\-\[\]()'",!?:]""")
    private val MULTI_SPACE = Pattern.compile("""\s+""")
    private val SIZE_SUFFIX = Pattern.compile("""\.(mkv|mp4|avi|mov|wmv|m4v|ts|m2ts)$""", Pattern.CASE_INSENSITIVE)

    fun isHebrew(s: String): Boolean {
        return s.any { it.code in 0x0590..0x05FF }
    }

    fun cleanTitle(title: String): String {
        val stripped = title.replace(":", "").replace("  ", " ").trim()
        val normalized = Normalizer.normalize(stripped, Normalizer.Form.NFKD)
        val sb = StringBuilder()
        for (c in normalized) {
            if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    fun normalize(text: String): String {
        var t = SIZE_SUFFIX.matcher(text).replaceAll("")
        t = NOISE.matcher(t).replaceAll(" ")
        t = MULTI_SPACE.matcher(t).replaceAll(" ")
        return t.trim().lowercase()
    }

    fun extractSeasonEpisode(text: String): Pair<Int, Int>? {
        val m = EPISODE_PATTERN.matcher(text)
        if (m.find()) {
            val s = m.group(1) ?: m.group(3) ?: m.group(5) ?: m.group(7)
            val e = m.group(2) ?: m.group(4) ?: m.group(6) ?: m.group(8)
            if (s != null && e != null) {
                val sVal = s.toIntOrNull()
                val eVal = e.toIntOrNull()
                if (sVal != null && eVal != null) return Pair(sVal, eVal)
            }
        }
        val norm = normalize(text)
        val mNorm = EPISODE_PATTERN.matcher(norm)
        if (mNorm.find()) {
            val s = mNorm.group(1) ?: mNorm.group(3) ?: mNorm.group(5) ?: mNorm.group(7)
            val e = mNorm.group(2) ?: mNorm.group(4) ?: mNorm.group(6) ?: mNorm.group(8)
            if (s != null && e != null) {
                val sVal = s.toIntOrNull()
                val eVal = e.toIntOrNull()
                if (sVal != null && eVal != null) return Pair(sVal, eVal)
            }
        }
        return null
    }

    fun extractEpisodeOnly(text: String): Int? {
        val m = EPISODE_ONLY_PATTERN.matcher(text)
        if (m.find()) {
            val e = m.group(1) ?: m.group(2) ?: m.group(3)
            val eVal = e?.toIntOrNull()
            if (eVal != null) return eVal
        }
        val mNorm = EPISODE_ONLY_PATTERN.matcher(normalize(text))
        if (mNorm.find()) {
            val e = mNorm.group(1) ?: mNorm.group(2) ?: mNorm.group(3)
            val eVal = e?.toIntOrNull()
            if (eVal != null) return eVal
        }
        return null
    }

    fun extractYears(text: String): List<Int> {
        val m = YEAR_PATTERN.matcher(text)
        val years = mutableListOf<Int>()
        while (m.find()) {
            m.group().toIntOrNull()?.let { years.add(it) }
        }
        return years
    }

    /**
     * Scores a Telegram message match against movie/series query details.
     * Score ranges from 0 to 100. Returns 0 if title or mandatory parameters do not match.
     */
    fun score(
        fileName: String,
        caption: String,
        title: String,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        localizedTitle: String? = null,
        englishTitle: String? = null
    ): Int {
        val combined = "$fileName $caption"
        val normalizedCombined = normalize(combined)
        val normalizedTitle = normalize(title)
        val normalizedLocalized = localizedTitle?.let { normalize(it) }
        val normalizedEnglish = englishTitle?.let { normalize(it) }

        val engMatch = !normalizedEnglish.isNullOrBlank() && normalizedCombined.contains(normalizedEnglish)
        val locMatch = !normalizedLocalized.isNullOrBlank() && normalizedCombined.contains(normalizedLocalized)
        var appMatch = normalizedTitle.isNotBlank() && normalizedCombined.contains(normalizedTitle)

        if (!appMatch) {
            val titleWords = normalizedTitle.split(" ").filter { it.isNotBlank() }
            val combinedWords = normalizedCombined.split(" ").filter { it.isNotBlank() }.toSet()
            if (titleWords.isNotEmpty() && titleWords.all { combinedWords.contains(it) }) {
                appMatch = true
            }
        }

        if (!engMatch && !locMatch && !appMatch) {
            return 0
        }

        var score = 60

        // Year evaluation
        if (year != null) {
            val fileYears = extractYears(combined)
            if (fileYears.contains(year)) {
                score += 20
            } else if (fileYears.any { Math.abs(it - year) == 1 }) {
                score += 5
            } else if (fileYears.isEmpty()) {
                score += 5
            } else {
                score -= 10
            }
        }

        // Season & Episode evaluation
        if (season != null && episode != null) {
            val seFile = extractSeasonEpisode(fileName)
            val seCaption = extractSeasonEpisode(caption)
            val rightSe = (seFile != null && seFile.first == season && seFile.second == episode) ||
                          (seCaption != null && seCaption.first == season && seCaption.second == episode)

            if (rightSe) {
                score += 20
            } else if (seFile != null || seCaption != null) {
                return 0
            } else if (season == 1) {
                val epFile = extractEpisodeOnly(fileName)
                val epCaption = extractEpisodeOnly(caption)
                if (epFile == episode || epCaption == episode) {
                    score += 20
                } else if (epFile != null || epCaption != null) {
                    return 0
                } else {
                    score -= 10
                }
            } else {
                score -= 10
            }
        } else if (season == null) {
            if (EPISODE_PATTERN.matcher(combined).find() || EPISODE_PATTERN.matcher(normalizedCombined).find()) {
                score -= 20
            }
        }

        return Math.max(0, Math.min(100, score))
    }

    /**
     * Ported from Telegram-stremio utils.normalize_release_name
     * Normalizes release filename for deduplication.
     */
    fun normalizeReleaseName(name: String): String {
        if (name.isBlank()) return ""
        var s = name.lowercase().replace(Regex("""\.[a-z0-9]{2,5}$"""), "")
        s = s.replace(Regex("""\[.*?\]|\(.*?\)|@\S+[\s:\-_|]*|\{.*?\}"""), " ")
        s = s.replace(Regex("""[^a-z0-9]"""), "")
        return s.trim()
    }

    /**
     * Builds search queries for movies matching Telegram-stremio logic.
     */
    fun buildMovieQueries(
        title: String,
        year: Int? = null,
        localizedTitle: String? = null,
        englishTitle: String? = null
    ): List<String> {
        val primary = cleanTitle(englishTitle ?: title)
        val localized = localizedTitle?.let { cleanTitle(it) }

        val queries = mutableListOf<String>()
        if (year != null) {
            queries.add("$primary $year")
        }
        queries.add(primary)

        if (primary.contains("&")) {
            queries.add(primary.replace("&", "and"))
            queries.add(primary.replace("&", " ").replace(Regex("""\s+"""), " ").trim())
        } else if (Regex("""\band\b""", RegexOption.IGNORE_CASE).containsMatchIn(primary)) {
            queries.add(primary.replace(Regex("""\band\b""", RegexOption.IGNORE_CASE), "&"))
        }

        if (localized != null && !localized.equals(primary, ignoreCase = true)) {
            if (year != null) {
                queries.add("$localized $year")
            }
            queries.add(localized)
            if (localized.contains("&")) {
                queries.add(localized.replace("&", "and"))
                queries.add(localized.replace("&", " ").replace(Regex("""\s+"""), " ").trim())
            }
        }

        val seen = mutableSetOf<String>()
        return queries.filter { seen.add(it.lowercase()) }
    }

    /**
     * Builds search queries for series matching Telegram-stremio logic.
     */
    fun buildSeriesQueries(
        title: String,
        season: Int,
        episode: Int,
        localizedTitle: String? = null,
        englishTitle: String? = null,
        languageCode: String = "en"
    ): List<String> {
        val engBase = cleanTitle(englishTitle ?: title)
        val locBase = localizedTitle?.let { cleanTitle(it) }
        val titlesAreSame = locBase == null || locBase.equals(engBase, ignoreCase = true)

        val s = season.toString()
        val e = episode.toString()
        val s2 = String.format("%02d", season)
        val e2 = String.format("%02d", episode)

        val queries = mutableListOf<String>()

        if (languageCode == "he") {
            val hebTitle = if (titlesAreSame) engBase else (locBase ?: engBase)
            queries.add("$hebTitle ע$s פ$e")
            queries.add("$hebTitle ע${s}פ$e")
            queries.add("$hebTitle עונה $s פרק $e")
            if (season == 1) {
                queries.add("$hebTitle פ$e")
                queries.add("$hebTitle פרק $e")
            }
        }

        if (!titlesAreSame && locBase != null) {
            queries.add("$locBase s${s}e${e}")
            queries.add("$locBase s${s2}e${e2}")
            queries.add("$locBase s$s e$e")
            queries.add("$locBase s$s2 e$e2")
            queries.add("$locBase ${s}x${e2}")
            queries.add("$locBase ${s}x${e}")
            queries.add(locBase)
        }

        queries.add("$engBase s${s}e${e}")
        queries.add("$engBase s${s2}e${e2}")
        queries.add("$engBase s$s e$e")
        queries.add("$engBase s$s2 e$e2")
        queries.add("$engBase ${s}x${e2}")
        queries.add("$engBase ${s}x${e}")
        queries.add("$engBase season $s episode $e")
        queries.add("$engBase episode $e")
        queries.add("$engBase season $s")
        queries.add(engBase)

        val seen = mutableSetOf<String>()
        return queries.map { it.lowercase() }.filter { seen.add(it) }
    }

    /**
     * Parses video quality from raw string.
     */
    fun parseQuality(raw: String): String {
        val t = raw.lowercase().replace(' ', '.')
        if (listOf("dvdscr", "screener", ".scr.").any { t.contains(it) }) return "SCR"
        if (listOf(".cam.", "camrip", "hdcam", "hdts", "telesync").any { t.contains(it) }) return "CAM"
        if (listOf("2160", "216o", ".4k.", ".uhd.", "ultrahd").any { t.contains(it) }) return "4K"
        if (listOf("1080", "1o8o", "108o", "1o80", ".fhd.").any { t.contains(it) }) return "1080p"
        if (listOf("720", "72o").any { t.contains(it) }) return "720p"
        if (listOf("480", "48o").any { t.contains(it) }) return "480p"
        if (listOf("360", "36o").any { t.contains(it) }) return "360p"
        return "Unknown"
    }

    /**
     * Returns quality tier integer (higher is better quality).
     */
    fun qualityTier(quality: String): Int {
        return when (quality.uppercase()) {
            "4K" -> 6
            "1080P" -> 5
            "720P" -> 4
            "480P" -> 3
            "360P" -> 2
            "CAM" -> -1
            "SCR" -> -1
            else -> 0
        }
    }
}
