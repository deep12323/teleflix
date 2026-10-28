package com.teleflix.app

import java.util.regex.Pattern

/**
 * High-precision media filename parser for auto-detecting Movies and TV Series from Telegram files.
 * Strips release groups, spam prefixes, resolutions, and audio tags.
 */
object MediaParser {

    data class ParsedMedia(
        val rawName: String,
        val cleanTitle: String,
        val year: Int?,
        val isSeries: Boolean,
        val season: Int?,
        val episode: Int?,
        val quality: String,
        val codec: String
    )

    private val VIDEO_EXT = Pattern.compile("""\.(mkv|mp4|avi|mov|wmv|m4v|webm|ts|m2ts)$""", Pattern.CASE_INSENSITIVE)

    // Match S01E02, 1x02, Season 1 Episode 2, S01-E02, etc.
    private val SE_PATTERN = Pattern.compile(
        """[Ss](\d{1,2})[.\s_-]*[Ee](\d{1,4})""" +
        """|(?<!\d)(\d{1,2})[xX](\d{1,4})(?!\d)""" +
        """|[Ss]eason\s*(\d{1,2})\s*[Ee]pisode\s*(\d{1,4})""" +
        """|[Ss]eason\s*(\d{1,2})\s*[-_.]*\s*[Ee]p?\s*(\d{1,4})""",
        Pattern.CASE_INSENSITIVE
    )

    // Match Episode-only: Ep 01, Episode 12
    private val EPISODE_ONLY = Pattern.compile(
        """(?:[Ee]pisode|[Ee]p)[.\s_-]*(\d{1,4})\b""",
        Pattern.CASE_INSENSITIVE
    )

    // Year pattern: 1920 - 2099
    private val YEAR_PATTERN = Pattern.compile("""\b(19\d{2}|20\d{2})\b""")

    // Common resolutions
    private val QUALITY_PATTERN = Pattern.compile("""\b(2160p|4k|uhd|1080p|1080i|720p|480p|576p|360p)\b""", Pattern.CASE_INSENSITIVE)

    // Common codecs and formats
    private val CODEC_PATTERN = Pattern.compile("""\b(hevc|x265|h265|x264|h264|avc|10bit|8bit|hdr10\+?|hdr|dv|dolby\s*vision|remux|bluray|bdrip|web-?dl|webrip|dvdrip|hdtv)\b""", Pattern.CASE_INSENSITIVE)

    // Spam domain and promo prefixes: e.g. [MkvKing.com], 1TamilMV.yt -, @channel_name -
    private val SPAM_PREFIX = Pattern.compile(
        """^(?:\[[^\]]+\]|\([^\)]+\))\s*[-_–]?\s*""" +
        """|^(?:https?://\S+|www\.\S+|[a-zA-Z0-9.\-_]+\.(?:com|org|net|yt|in|vip|top|cc|me|to))\s*[-_–:]\s*""" +
        """|^@[a-zA-Z0-9_]{3,32}\s*[-_–:]\s*""",
        Pattern.CASE_INSENSITIVE
    )

    // Noise characters to replace with spaces
    private val NOISE = Pattern.compile("""[._\-+]+""")

    fun parse(fileName: String, caption: String = ""): ParsedMedia {
        // Strip video container extension
        var work = VIDEO_EXT.matcher(fileName.trim()).replaceAll("")

        // Strip leading release tags or spam domains
        work = SPAM_PREFIX.matcher(work).replaceAll("").trim()

        // Extract Quality
        val qm = QUALITY_PATTERN.matcher(work)
        val quality = if (qm.find()) qm.group(1).uppercase() else {
            val qCaption = QUALITY_PATTERN.matcher(caption)
            if (qCaption.find()) qCaption.group(1).uppercase() else ""
        }

        // Extract Codec
        val cm = CODEC_PATTERN.matcher(work)
        val codec = if (cm.find()) cm.group(1).uppercase() else ""

        // Check for Season and Episode
        var isSeries = false
        var season: Int? = null
        var episode: Int? = null
        var titlePart = work

        val seMatcher = SE_PATTERN.matcher(work)
        if (seMatcher.find()) {
            isSeries = true
            val sStr = seMatcher.group(1) ?: seMatcher.group(3) ?: seMatcher.group(5) ?: seMatcher.group(7)
            val eStr = seMatcher.group(2) ?: seMatcher.group(4) ?: seMatcher.group(6) ?: seMatcher.group(8)
            season = sStr?.toIntOrNull()
            episode = eStr?.toIntOrNull()
            titlePart = work.substring(0, seMatcher.start())
        } else {
            // Check episode-only
            val epMatcher = EPISODE_ONLY.matcher(work)
            if (epMatcher.find()) {
                isSeries = true
                season = 1
                episode = epMatcher.group(1)?.toIntOrNull()
                titlePart = work.substring(0, epMatcher.start())
            }
        }

        // Extract Year
        var year: Int? = null
        val ym = YEAR_PATTERN.matcher(titlePart)
        if (ym.find()) {
            year = ym.group(1).toIntOrNull()
            titlePart = titlePart.substring(0, ym.start())
        } else if (!isSeries) {
            val ymFull = YEAR_PATTERN.matcher(work)
            if (ymFull.find()) {
                year = ymFull.group(1).toIntOrNull()
                titlePart = work.substring(0, ymFull.start())
            }
        }

        // Clean up title part
        var clean = NOISE.matcher(titlePart).replaceAll(" ")
        clean = clean.replace(Regex("""\(.*?\)|\[.*?\]"""), " ")

        // Strip any trailing audio/quality keywords if they leaked into the title
        clean = clean.replace(Regex("""\b(2160p|4k|1080p|720p|480p|uhd|bluray|web-?dl|webrip|hdr|hevc|x265|x264|ddp5\.1|atmos|hindi|tamil|telugu|english|dual\s*audio|multi\s*audio|sub|esub)\b.*""", RegexOption.IGNORE_CASE), "")

        // Strip non-alphanumeric punctuation from borders
        clean = clean.replace(Regex("""^[^\w]+|[^\w]+$"""), "").trim()

        if (clean.isBlank()) {
            clean = NOISE.matcher(work).replaceAll(" ").replace(Regex("""^[^\w]+|[^\w]+$"""), "").trim()
        }

        return ParsedMedia(
            rawName = fileName,
            cleanTitle = clean,
            year = year,
            isSeries = isSeries,
            season = season,
            episode = episode,
            quality = quality,
            codec = codec
        )
    }
}
