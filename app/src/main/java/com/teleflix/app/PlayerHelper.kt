package com.teleflix.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

object PlayerHelper {

    var activeMediaIdForResume: String = ""
    var activeStreamUrlForResume: String = ""
    var activeTitleForResume: String = ""

    fun saveLinkToWatchHistory(
        context: Context,
        streamUrl: String,
        title: String,
        posterUrl: String = "",
        mediaId: String = "",
        originalFileName: String = ""
    ) {
        val item = MediaItem(
            id = if (mediaId.isNotBlank()) mediaId else "history_${System.currentTimeMillis()}",
            title = title,
            posterUrl = posterUrl,
            year = "Watched",
            rating = "▶",
            overview = "Playing stream: $title",
            type = "telegram_media",
            streamUrl = streamUrl,
            originalFileName = originalFileName
        )
        saveToHistory(context, item)
    }

    fun saveToHistory(context: Context, item: MediaItem) {
        try {
            val prefs = context.getSharedPreferences("teleflix_watch_history", Context.MODE_PRIVATE)
            val jsonArray = org.json.JSONArray(prefs.getString("history_items", "[]"))
            val list = mutableListOf<org.json.JSONObject>()
            for (i in 0 until jsonArray.length()) {
                list.add(jsonArray.getJSONObject(i))
            }
            list.removeAll {
                it.optString("id") == item.id ||
                it.optString("streamUrl") == item.streamUrl ||
                it.optString("title") == item.title
            }
            val newObj = org.json.JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("posterUrl", item.posterUrl)
                put("year", item.year)
                put("rating", item.rating)
                put("overview", item.overview)
                put("type", item.type)
                put("streamUrl", item.streamUrl)
                put("originalFileName", item.originalFileName)
                put("timestamp", System.currentTimeMillis())
            }
            list.add(0, newObj)
            val finalArray = org.json.JSONArray()
            list.take(100).forEach { finalArray.put(it) }
            prefs.edit().putString("history_items", finalArray.toString()).apply()
        } catch (_: Exception) {}
    }

    fun checkResumeAndSelectPlayer(
        activity: Activity,
        streamUrl: String,
        title: String,
        posterUrl: String = "",
        mediaId: String = "",
        originalFileName: String = "",
        skipSplitCheck: Boolean = false
    ) {
        val isZip = mediaId.startsWith("zip_") ||
                    TelegramRepository.isZipArchiveFilename(originalFileName) ||
                    TelegramRepository.isZipArchiveFilename(title) ||
                    originalFileName.endsWith(".zip", ignoreCase = true) ||
                    title.contains(".zip", ignoreCase = true) ||
                    streamUrl.contains(".zip", ignoreCase = true)

        if (!isZip && !skipSplitCheck && mediaId.isNotBlank()) {
            val cached = TelegramRepository.groupPartsCache[mediaId]
            if (cached != null && cached.size > 1) {
                val hasZipPart = cached.any { TelegramRepository.isZipArchiveFilename(it.fileName) || it.fileName.endsWith(".zip", ignoreCase = true) }
                if (!hasZipPart) {
                    showGroupPartsSelectionDialog(
                        activity,
                        mediaId,
                        streamUrl,
                        title,
                        posterUrl,
                        originalFileName,
                        cached
                    )
                    return
                }
            }
        }
        saveLinkToWatchHistory(activity, streamUrl, title, posterUrl, mediaId, originalFileName)
        val prefsLink = activity.getSharedPreferences("teleflix_resume_points", Context.MODE_PRIVATE)
        val prefsTitle = activity.getSharedPreferences("TeleflixResume", Context.MODE_PRIVATE)
        var savedPositionMs = 0L

        if (mediaId.isNotBlank()) {
            savedPositionMs = prefsLink.getLong("id_$mediaId", 0L)
            if (savedPositionMs <= 3_000L) {
                savedPositionMs = prefsLink.getLong(mediaId, 0L)
            }
        }
        if (savedPositionMs <= 3_000L && streamUrl.isNotBlank()) {
            savedPositionMs = prefsLink.getLong(streamUrl, 0L)
        }
        if (savedPositionMs <= 3_000L) {
            savedPositionMs = prefsTitle.getLong("resume_$title", 0L)
        }

        if (savedPositionMs > 3_000L) {
            val alwaysResume = activity.getSharedPreferences("teleflix_preferences", Context.MODE_PRIVATE)
                .getBoolean("always_resume", false)
            if (alwaysResume) {
                handlePlayerLaunch(activity, streamUrl, title, savedPositionMs, mediaId)
            } else {
                val formattedTime = formatMillisToTime(savedPositionMs)
                AlertDialog.Builder(activity)
                    .setTitle("Resume Playback")
                    .setMessage("You previously watched '$title' up to $formattedTime.\n\nDo you want to resume where you left off or start from the beginning?")
                    .setPositiveButton("▶ Resume ($formattedTime)") { _, _ ->
                        handlePlayerLaunch(activity, streamUrl, title, savedPositionMs, mediaId)
                    }
                    .setNegativeButton("🔄 Start Over") { _, _ ->
                        val editLink = prefsLink.edit()
                        val editTitle = prefsTitle.edit()
                        if (mediaId.isNotBlank()) {
                            editLink.remove("id_$mediaId").remove(mediaId).remove("dur_id_$mediaId").remove("dur_$mediaId")
                        }
                        editLink.remove(streamUrl).remove("dur_$streamUrl")
                        editTitle.remove("resume_$title").remove("dur_$title")
                        editLink.apply()
                        editTitle.apply()
                        handlePlayerLaunch(activity, streamUrl, title, 0L, mediaId)
                    }
                    .setNeutralButton("Cancel", null)
                    .show()
            }
        } else {
            handlePlayerLaunch(activity, streamUrl, title, 0L, mediaId)
        }
    }

    fun handlePlayerLaunch(
        activity: Activity,
        streamUrl: String,
        title: String,
        resumeMs: Long,
        mediaId: String = ""
    ) {
        val prefPlayer = activity.getSharedPreferences("teleflix_preferences", Context.MODE_PRIVATE)
            .getString("default_player", "ask") ?: "ask"
        if (prefPlayer == "ask") {
            showPlayerActionDialog(activity, streamUrl, title, resumeMs, mediaId)
        } else {
            openStreamInPlayer(activity, prefPlayer, streamUrl, title, resumeMs, mediaId)
        }
    }

    fun showPlayerActionDialog(
        activity: Activity,
        streamUrl: String,
        title: String,
        resumeMs: Long = 0L,
        mediaId: String = ""
    ) {
        val options = arrayOf(
            "⚡ External ExoPlayer (Just Player / Next Player)",
            "🔵 External MPV Player (is.xyz.mpv)",
            "🔴 External MPVEX Player",
            "🧡 External VLC Player",
            "📱 Choose From All Installed Players..."
        )
        val keys = arrayOf("exo", "mpv", "mpvex", "vlc", "chooser")

        fun dp(v: Int) = UITheme.dpToPx(activity, v)

        val scrollView = ScrollView(activity).apply {
            setBackgroundColor(Color.parseColor(UITheme.BACKGROUND))
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }

        val headerTitle = TextView(activity).apply {
            text = "Select Video Player"
            UITheme.applySectionTitleStyle(this)
            setTextColor(Color.WHITE)
        }
        container.addView(headerTitle)

        val headerSub = TextView(activity).apply {
            text = "Playing: $title"
            UITheme.applyMetadataStyle(this)
            setPadding(0, dp(4), 0, dp(14))
        }
        container.addView(headerSub)

        var dialog: AlertDialog? = null

        for (i in options.indices) {
            val playerKey = keys[i]
            val card = TextView(activity).apply {
                text = options[i]
                UITheme.applyCardTitleStyle(this)
                background = UITheme.createRippleCardShape(activity, UITheme.CARD, 14, UITheme.STROKE_COLOR)
                val pV = dp(12)
                val pH = dp(16)
                setPadding(pH, pV, pH, pV)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, dp(8))
                }
                setOnClickListener {
                    dialog?.dismiss()
                    openStreamInPlayer(activity, playerKey, streamUrl, title, resumeMs, mediaId)
                }
            }
            container.addView(card)
        }

        scrollView.addView(container)

        dialog = AlertDialog.Builder(activity)
            .setView(scrollView)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    fun openStreamInPlayer(
        activity: Activity,
        playerType: String,
        streamUrl: String,
        title: String,
        resumeMs: Long,
        mediaId: String = ""
    ) {
        activeMediaIdForResume = mediaId
        activeStreamUrlForResume = streamUrl
        activeTitleForResume = title

        // Immediately pre-warm TDLib download for offset 0
        val fileId = streamUrl.substringAfter("/file/", "").substringBefore("/").substringBefore("?").toIntOrNull()
        if (fileId != null) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    TelegramClient.sendRequest(TdApi.DownloadFile().also { req ->
                        req.fileId = fileId
                        req.priority = 32
                        req.offset = 0
                        req.limit = 1048576
                        req.synchronous = false
                    })
                }
            }
        }

        val cleanDisplayTitle = title.replace(Regex("""^[📦🗄️⚡🎬🎥📽️\s]+"""), "").trim()
        val isPlaylist = streamUrl.contains("/playlist/") || streamUrl.lowercase().contains(".m3u8") || streamUrl.lowercase().contains(".m3u")
        val isAudio = !isPlaylist && Regex("""(?i)\.(mp3|flac|aac|ogg|opus|wav|wma|m4a|aiff)$""").containsMatchIn(cleanDisplayTitle)
        val isMkv = !isPlaylist && !isAudio && (cleanDisplayTitle.endsWith(".mkv", ignoreCase = true) || streamUrl.lowercase().contains(".mkv") || streamUrl.contains("/merged/") || streamUrl.contains("/zip/"))
        val mimeType = when {
            isPlaylist -> "application/vnd.apple.mpegurl"
            isAudio -> "audio/*"
            isMkv -> "video/x-matroska"
            else -> "video/mp4"
        }

        val videoSafeTitle = if (!isPlaylist && !isAudio && !cleanDisplayTitle.endsWith(".mkv", ignoreCase = true) && !cleanDisplayTitle.endsWith(".mp4", ignoreCase = true)) {
            val base = cleanDisplayTitle.removeSuffix(".zip").removeSuffix(".7z").removeSuffix(".rar").trim()
            "$base.mkv"
        } else cleanDisplayTitle

        val baseIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(streamUrl), mimeType)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("title", videoSafeTitle)
            putExtra("filename", videoSafeTitle)
            putExtra("uri", streamUrl)
            putExtra("url", streamUrl)
            putExtra("org.videolan.vlc.player.ForceVideo", true)
            putExtra("ForceVideo", true)
            putExtra("return_result", true)
            if (resumeMs > 0) {
                putExtra("position", resumeMs.toInt())
                putExtra("extra_position", resumeMs)
                putExtra("resume_position", resumeMs)
                putExtra("position_ms", resumeMs)
                putExtra("start_position", resumeMs)
                putExtra("from_start", false)
            } else {
                putExtra("position", 0)
                putExtra("extra_position", 0L)
                putExtra("resume_position", 0L)
                putExtra("position_ms", 0L)
                putExtra("start_position", 0L)
                putExtra("from_start", true)
            }
        }

        val pm = activity.packageManager

        when (playerType) {
            "exo" -> {
                val explicitToTry = listOf(
                    "com.brouken.player" to "com.brouken.player.PlayerActivity",
                    "dev.anilbeesetti.nextplayer" to "dev.anilbeesetti.nextplayer.MainActivity",
                    "dev.anilbeesetti.nextplayer" to "dev.anilbeesetti.nextplayer.ui.PlayerActivity"
                )
                val packagesToTry = listOf(
                    "com.brouken.player",
                    "dev.anilbeesetti.nextplayer",
                    "dev.anilbeesetti.nextplayer.play",
                    "dev.anilbeesetti.nextplayer.debug",
                    "dev.anilbeesetti.nextplayer.preview",
                    "com.nextplayer.app",
                    "com.google.android.exoplayer",
                    "com.mxtech.videoplayer.ad",
                    "com.mxtech.videoplayer.pro",
                    "org.courville.nova"
                )

                var launched = false
                for ((pkg, cls) in explicitToTry) {
                    if (tryLaunchPackage(activity, baseIntent, streamUrl, pkg, cls)) {
                        launched = true
                        break
                    }
                }
                if (!launched) {
                    for (pkg in packagesToTry) {
                        if (tryLaunchPackage(activity, baseIntent, streamUrl, pkg)) {
                            launched = true
                            break
                        }
                    }
                }

                if (!launched) {
                    try {
                        val resolveInfo = pm.queryIntentActivities(
                            Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.parse(streamUrl), "video/*") },
                            0
                        )
                        val exoMatch = resolveInfo.firstOrNull { 
                            val pkgName = it.activityInfo.packageName.lowercase()
                            val label = it.loadLabel(pm).toString().lowercase()
                            pkgName.contains("brouken") || pkgName.contains("nextplayer") || label.contains("just player") || label.contains("next player") || label.contains("exo")
                        }
                        if (exoMatch != null) {
                            launched = tryLaunchPackage(activity, baseIntent, streamUrl, exoMatch.activityInfo.packageName, exoMatch.activityInfo.name)
                        }
                    } catch (_: Exception) {}
                }

                if (!launched) {
                    showPlayerNotFoundDialog(activity, baseIntent, "Just Player (ExoPlayer)", "https://github.com/brouken/just-player/releases")
                }
            }

            "mpv" -> {
                // Internal Preferred Player: MPV Android (is.xyz.mpv)
                val explicitToTry = listOf(
                    "is.xyz.mpv" to "is.xyz.mpv.MPVActivity",
                    "is.xyz.mpv.debug" to "is.xyz.mpv.MPVActivity"
                )
                val packagesToTry = listOf(
                    "is.xyz.mpv",
                    "is.xyz.mpv.debug",
                    "id.nzxm.mpv"
                )

                var launched = false
                for ((pkg, cls) in explicitToTry) {
                    if (tryLaunchPackage(activity, baseIntent, streamUrl, pkg, cls)) {
                        launched = true
                        break
                    }
                }
                if (!launched) {
                    for (pkg in packagesToTry) {
                        if (tryLaunchPackage(activity, baseIntent, streamUrl, pkg)) {
                            launched = true
                            break
                        }
                    }
                }

                if (!launched) {
                    try {
                        val resolveInfo = pm.queryIntentActivities(
                            Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.parse(streamUrl), "video/*") },
                            0
                        )
                        val mpvMatch = resolveInfo.firstOrNull { 
                            val pkgName = it.activityInfo.packageName.lowercase()
                            val label = it.loadLabel(pm).toString().lowercase()
                            pkgName == "is.xyz.mpv" || pkgName == "is.xyz.mpv.debug" || label.contains("mpv")
                        }
                        if (mpvMatch != null) {
                            launched = tryLaunchPackage(activity, baseIntent, streamUrl, mpvMatch.activityInfo.packageName, mpvMatch.activityInfo.name)
                        }
                    } catch (_: Exception) {}
                }

                if (!launched) {
                    showPlayerNotFoundDialog(
                        activity,
                        baseIntent,
                        "MPV Player (is.xyz.mpv)",
                        "https://play.google.com/store/apps/details?id=is.xyz.mpv"
                    )
                }
            }

            "mpvex" -> {
                val explicitToTry = listOf(
                    "app.marlboroadvance.mpvex" to "app.marlboroadvance.mpvex.ui.player.PlayerActivity",
                    "app.marlboroadvance.mpvex.debug" to "app.marlboroadvance.mpvex.ui.player.PlayerActivity",
                    "com.marlboroadvance.mpvex" to "com.marlboroadvance.mpvex.ui.player.PlayerActivity",
                    "id.nzxm.mpvex" to "id.nzxm.mpvex.ui.player.PlayerActivity",
                    "id.nzxm.mpvex.debug" to "id.nzxm.mpvex.ui.player.PlayerActivity",
                    "id.nzxm.mpv" to "is.xyz.mpv.MPVActivity",
                    "com.mpvex.app" to "com.mpvex.app.ui.player.PlayerActivity"
                )
                val packagesToTry = listOf(
                    "app.marlboroadvance.mpvex",
                    "app.marlboroadvance.mpvex.debug",
                    "com.marlboroadvance.mpvex",
                    "id.nzxm.mpvex",
                    "id.nzxm.mpvex.debug",
                    "com.mpvex.app"
                )

                var launched = false
                for ((pkg, cls) in explicitToTry) {
                    if (tryLaunchPackage(activity, baseIntent, streamUrl, pkg, cls)) {
                        launched = true
                        break
                    }
                }
                if (!launched) {
                    for (pkg in packagesToTry) {
                        if (tryLaunchPackage(activity, baseIntent, streamUrl, pkg)) {
                            launched = true
                            break
                        }
                    }
                }

                if (!launched) {
                    try {
                        val resolveInfo = pm.queryIntentActivities(
                            Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.parse(streamUrl), "video/*") },
                            0
                        )
                        val mpvMatch = resolveInfo.firstOrNull { 
                            val pkgName = it.activityInfo.packageName.lowercase()
                            val label = it.loadLabel(pm).toString().lowercase()
                            pkgName.contains("mpvex") || label.contains("mpvex")
                        }
                        if (mpvMatch != null) {
                            launched = tryLaunchPackage(activity, baseIntent, streamUrl, mpvMatch.activityInfo.packageName, mpvMatch.activityInfo.name)
                        }
                    } catch (_: Exception) {}
                }

                if (!launched) {
                    showPlayerNotFoundDialog(activity, baseIntent, "MPVEX Player", "https://github.com/marlboro-advance/mpvEx/releases")
                }
            }

            "vlc" -> {
                val explicitToTry = listOf(
                    "org.videolan.vlc" to "org.videolan.vlc.gui.video.VideoPlayerActivity"
                )
                val packagesToTry = listOf("org.videolan.vlc")

                var launched = false
                for ((pkg, cls) in explicitToTry) {
                    if (tryLaunchPackage(activity, baseIntent, streamUrl, pkg, cls)) {
                        launched = true
                        break
                    }
                }
                if (!launched) {
                    for (pkg in packagesToTry) {
                        if (tryLaunchPackage(activity, baseIntent, streamUrl, pkg)) {
                            launched = true
                            break
                        }
                    }
                }

                if (!launched) {
                    showPlayerNotFoundDialog(activity, baseIntent, "VLC Player", "https://play.google.com/store/apps/details?id=org.videolan.vlc")
                }
            }

            else -> {
                val chooser = Intent.createChooser(baseIntent, "Play With...")
                try {
                    activity.startActivity(chooser)
                } catch (e: Exception) {
                    Toast.makeText(activity, "No video players installed!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun tryLaunchPackage(
        activity: Activity,
        baseIntent: Intent,
        streamUrl: String,
        pkg: String,
        explicitActivity: String? = null
    ): Boolean {
        val pm = activity.packageManager

        // 1. Try explicit class name if specified
        if (!explicitActivity.isNullOrBlank()) {
            try {
                val intent = Intent(baseIntent).apply {
                    setClassName(pkg, explicitActivity)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(intent)
                return true
            } catch (_: Exception) {}

            try {
                val intent = Intent(baseIntent).apply {
                    setClassName(pkg, explicitActivity)
                    setDataAndType(Uri.parse(streamUrl), "video/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(intent)
                return true
            } catch (_: Exception) {}

            return false
        }

        // 2. Try setPackage with base mimeType
        try {
            val intent = Intent(baseIntent).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
            return true
        } catch (_: Exception) {}

        // 3. Try setPackage with video/*
        try {
            val intent = Intent(baseIntent).apply {
                setPackage(pkg)
                setDataAndType(Uri.parse(streamUrl), "video/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
            return true
        } catch (_: Exception) {}

        // 4. Query package's activities matching ACTION_VIEW with video/*
        try {
            val queryIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(streamUrl), "video/*")
                setPackage(pkg)
            }
            val matches = pm.queryIntentActivities(queryIntent, 0)
            if (matches.isNotEmpty()) {
                val targetActivity = matches[0].activityInfo.name
                val intent = Intent(baseIntent).apply {
                    setClassName(pkg, targetActivity)
                    setDataAndType(Uri.parse(streamUrl), "video/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(intent)
                return true
            }
        } catch (_: Exception) {}

        return false
    }

    private fun showPlayerNotFoundDialog(
        activity: Activity,
        baseIntent: Intent,
        playerName: String,
        downloadUrl: String
    ) {
        val isPlayStore = downloadUrl.contains("play.google.com")
        AlertDialog.Builder(activity)
            .setTitle("⚠️ $playerName Not Installed")
            .setMessage("$playerName was not detected on your device.\n\nWould you like to select from your installed players or install $playerName?")
            .setPositiveButton("Choose Installed Player") { _, _ ->
                val chooser = Intent.createChooser(baseIntent, "Select Video Player")
                try { activity.startActivity(chooser) } catch (_: Exception) {
                    Toast.makeText(activity, "No video player found on device!", Toast.LENGTH_SHORT).show()
                }
            }
            .apply {
                if (downloadUrl.isNotBlank()) {
                    val btnLabel = if (isPlayStore) "Install from Play Store" else "Download $playerName"
                    setNeutralButton(btnLabel) { _, _ ->
                        if (isPlayStore) {
                            val pkg = downloadUrl.substringAfter("id=").substringBefore("&")
                            try {
                                val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                activity.startActivity(marketIntent)
                                return@setNeutralButton
                            } catch (_: Exception) {}
                        }
                        try {
                            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        } catch (_: Exception) {
                            Toast.makeText(activity, "Could not open browser or Play Store", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun formatMillisToTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    fun extractPartNumber(name: String): Int {
        val partMatch = Regex("""[\._\s-]*(?:part|pt|cd)[\._\s-]*(\d{1,4})""", RegexOption.IGNORE_CASE).find(name)
            ?: Regex("""\((?:part|pt)?\s*(\d{1,4})\)""", RegexOption.IGNORE_CASE).find(name)
            ?: Regex("""\.\d{1,4}$""").find(name)
        return partMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    fun showGroupPartsSelectionDialog(
        activity: Activity,
        mediaId: String,
        streamUrl: String,
        title: String,
        posterUrl: String,
        originalFileName: String,
        parts: List<TelegramVideoMessage>
    ) {
        val validParts = parts
            .filter { it.fileSize > 0 }
            .distinctBy { if (it.messageId != 0L) "${it.chatId}_${it.messageId}" else it.fileName }
            .sortedWith(compareBy({ extractPartNumber(it.fileName) }, { it.messageId }, { it.fileName }))
        val finalParts = if (validParts.isNotEmpty()) validParts else parts

        val scrollView = ScrollView(activity).apply {
            setBackgroundColor(Color.parseColor(UITheme.BACKGROUND))
        }
        val cardList = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = UITheme.dpToPx(activity, 16)
            setPadding(pad, pad, pad, pad)
        }

        val baseName = originalFileName.ifBlank { title }
        val cleanTitle = baseName.removePrefix("📦 ").removePrefix("🗄️ ").trim()

        val headerText = TextView(activity).apply {
            text = "📂 $cleanTitle"
            UITheme.applySectionTitleStyle(this)
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        cardList.addView(headerText)

        val totalSize = finalParts.sumOf { it.fileSize }
        val totalSizeStr = TdlibManager.formatBytes(totalSize)

        val subHeaderText = TextView(activity).apply {
            text = "This video has ${finalParts.size} parts (Total: $totalSizeStr). Select an option:"
            UITheme.applyMetadataStyle(this)
            setPadding(0, 4, 0, 14)
        }
        cardList.addView(subHeaderText)

        var reverseOrder = false
        val reverseCheckBox = android.widget.CheckBox(activity).apply {
            text = "Reverse Part Order (Use if video fails to play)"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, isChecked -> reverseOrder = isChecked }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, UITheme.dpToPx(activity, 12))
            }
        }
        cardList.addView(reverseCheckBox)

        var dialog: AlertDialog? = null

        // Combined Stream Option Card
        val streamCombinedCard = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = UITheme.createRippleCardShape(activity, UITheme.SURFACE, 14, UITheme.PRIMARY)
            val p = UITheme.dpToPx(activity, 12)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, UITheme.dpToPx(activity, 8))
            }
            isClickable = true
            setOnClickListener {
                dialog?.dismiss()
                val activeParts = if (reverseOrder) finalParts.reversed() else finalParts
                val combinedMediaId = if (mediaId.startsWith("group_")) mediaId else "group_${activeParts.firstOrNull()?.chatId ?: 0}_$cleanTitle"
                TelegramRepository.groupPartsCache[combinedMediaId] = activeParts
                for (p in activeParts) {
                    val pId = "${p.chatId}_${p.messageId}"
                    val pName = p.fileName.ifBlank { "Part ${p.fileId}" }
                    val pUrl = TelegramRepository.getStreamUrl(p.fileId, p.fileName, p.fileSize, p.chatId, p.messageId)
                    saveToHistory(
                        activity,
                        MediaItem(
                            id = pId,
                            title = pName,
                            posterUrl = posterUrl,
                            year = "Watched",
                            rating = "▶",
                            overview = "Telegram file: $pName",
                            type = "telegram_media",
                            streamUrl = pUrl,
                            originalFileName = p.fileName,
                            fileSize = p.fileSize
                        )
                    )
                }
                CoroutineScope(Dispatchers.Main).launch {
                    val freshIds = activeParts.map { it.fileId }
                    val partSizes = activeParts.map { it.fileSize }
                    val groupChats = activeParts.map { it.chatId }
                    val groupMsgs = activeParts.map { it.messageId }
                    val urlToPlay = TelegramRepository.getMergedStreamUrl(freshIds, cleanTitle, partSizes, groupChats, groupMsgs)
                    val freshUrl = TelegramStreamingProxy.refreshUrl(urlToPlay)
                    checkResumeAndSelectPlayer(
                        activity,
                        freshUrl,
                        "📦 $cleanTitle (Combined)",
                        posterUrl,
                        combinedMediaId,
                        baseName,
                        skipSplitCheck = true
                    )
                }
            }
        }

        val stIcon = TextView(activity).apply {
            text = "🎬 ▶"
            textSize = 18f
            setPadding(0, 0, 12, 0)
        }
        streamCombinedCard.addView(stIcon)

        val stInfo = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val stTitle = TextView(activity).apply {
            text = "Stream Full Combined Video"
            UITheme.applyCardTitleStyle(this)
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        stInfo.addView(stTitle)

        val stSub = TextView(activity).apply {
            text = "Stream continuous merged playback of all ${finalParts.size} parts ($totalSizeStr)"
            UITheme.applyMetadataStyle(this)
            setTextColor(Color.parseColor(UITheme.PRIMARY))
        }
        stInfo.addView(stSub)
        streamCombinedCard.addView(stInfo)

        cardList.addView(streamCombinedCard)

        // Combined Download Option Card
        val downloadCombinedCard = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = UITheme.createRippleCardShape(activity, UITheme.SURFACE, 14, UITheme.PRIMARY)
            val p = UITheme.dpToPx(activity, 12)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, UITheme.dpToPx(activity, 12))
            }
            isClickable = true
            setOnClickListener {
                dialog?.dismiss()
                val activeParts = if (reverseOrder) finalParts.reversed() else finalParts
                DownloadManager.startMultiPartDownload(activity, cleanTitle, baseName, activeParts, posterUrl)
                Toast.makeText(activity, "Started downloading full combined video ($totalSizeStr) 📥", Toast.LENGTH_SHORT).show()
            }
        }

        val dlIcon = TextView(activity).apply {
            text = "⚡ 📥"
            textSize = 18f
            setPadding(0, 0, 12, 0)
        }
        downloadCombinedCard.addView(dlIcon)

        val dlInfo = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val dlTitle = TextView(activity).apply {
            text = "Download Full Combined Video"
            UITheme.applyCardTitleStyle(this)
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        dlInfo.addView(dlTitle)

        val dlSub = TextView(activity).apply {
            text = "Combines all ${finalParts.size} parts into a single file ($totalSizeStr)"
            UITheme.applyMetadataStyle(this)
            setTextColor(Color.parseColor(UITheme.PRIMARY))
        }
        dlInfo.addView(dlSub)
        downloadCombinedCard.addView(dlInfo)

        cardList.addView(downloadCombinedCard)

        // Individual Parts Options
        finalParts.forEachIndexed { index, part ->
            val partNumStr = String.format("%03d", index + 1)
            val partTitle = part.fileName.ifBlank { "Part $partNumStr" }
            val partSize = TdlibManager.formatBytes(part.fileSize)
            val partMediaId = "${part.chatId}_${part.messageId}"
            val displayPartTitle = if (partTitle.contains(cleanTitle, ignoreCase = true)) partTitle else "$cleanTitle - Part ${index + 1}"

            val partCard = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = UITheme.createRippleCardShape(activity, UITheme.CARD, 12, UITheme.STROKE_COLOR)
                val p = UITheme.dpToPx(activity, 10)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, UITheme.dpToPx(activity, 6))
                }
                isClickable = true
                setOnClickListener {
                    dialog?.dismiss()
                    CoroutineScope(Dispatchers.Main).launch {
                        val freshUrl = TelegramRepository.getFreshMediaUrl(part.chatId, part.messageId)
                        if (freshUrl != null && freshUrl.isNotBlank()) {
                            val groupId = if (mediaId.startsWith("group_")) mediaId else "group_${part.chatId}_$cleanTitle"
                            TelegramRepository.groupPartsCache[groupId] = finalParts
                            for (p in finalParts) {
                                val pId = "${p.chatId}_${p.messageId}"
                                val pName = p.fileName.ifBlank { "Part ${p.fileId}" }
                                val pUrl = TelegramRepository.getStreamUrl(p.fileId, p.fileName, p.fileSize, p.chatId, p.messageId)
                                saveToHistory(
                                    activity,
                                    MediaItem(
                                        id = pId,
                                        title = pName,
                                        posterUrl = posterUrl,
                                        year = "Watched",
                                        rating = "▶",
                                        overview = "Telegram file: $pName",
                                        type = "telegram_media",
                                        streamUrl = pUrl,
                                        originalFileName = p.fileName,
                                        fileSize = p.fileSize
                                    )
                                )
                            }
                            checkResumeAndSelectPlayer(
                                activity,
                                freshUrl,
                                displayPartTitle,
                                posterUrl,
                                partMediaId,
                                part.fileName,
                                skipSplitCheck = true
                            )
                        } else {
                            Toast.makeText(activity, "Media link expired", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            val iconText = TextView(activity).apply {
                text = "▶ Part ${index + 1}"
                UITheme.applyCardTitleStyle(this)
                textSize = 13f
                setTextColor(Color.parseColor(UITheme.PRIMARY))
                setPadding(0, 0, 12, 0)
            }
            partCard.addView(iconText)

            val partInfo = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val pTitle = TextView(activity).apply {
                text = partTitle
                UITheme.applyCardTitleStyle(this)
                textSize = 13f
            }
            partInfo.addView(pTitle)

            val pSub = TextView(activity).apply {
                text = "Size: $partSize"
                UITheme.applyMetadataStyle(this)
                textSize = 11f
            }
            partInfo.addView(pSub)

            partCard.addView(partInfo)
            cardList.addView(partCard)
        }

        scrollView.addView(cardList)

        dialog = AlertDialog.Builder(activity)
            .setView(scrollView)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
        val metrics = activity.resources.displayMetrics
        val w = (metrics.widthPixels * 0.92).toInt()
        val h = (metrics.heightPixels * 0.82).toInt()
        dialog.window?.setLayout(w, h)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor(UITheme.BACKGROUND)))
    }

    fun showGroupDownloadOptionsDialog(
        activity: Activity,
        mediaId: String,
        cleanTitle: String,
        originalFileName: String,
        posterUrl: String,
        parts: List<TelegramVideoMessage>,
        isZip: Boolean = false
    ) {
        val validParts = parts
            .filter { it.fileSize > 0 }
            .distinctBy { if (it.messageId != 0L) "${it.chatId}_${it.messageId}" else it.fileName }
            .sortedWith(compareBy({ extractPartNumber(it.fileName) }, { it.messageId }, { it.fileName }))
        val finalParts = if (validParts.isNotEmpty()) validParts else parts
        val totalSize = finalParts.sumOf { it.fileSize }
        val totalSizeStr = TdlibManager.formatBytes(totalSize)

        val scrollView = ScrollView(activity).apply {
            setBackgroundColor(Color.parseColor(UITheme.BACKGROUND))
        }
        val cardList = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = UITheme.dpToPx(activity, 16)
            setPadding(pad, pad, pad, pad)
        }

        val headerText = TextView(activity).apply {
            text = if (isZip) "🗄️ Download ZIP Archive" else "📥 Download Multi-Part Video"
            UITheme.applySectionTitleStyle(this)
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        cardList.addView(headerText)

        val subHeaderText = TextView(activity).apply {
            text = if (finalParts.size > 1) "'$cleanTitle' has ${finalParts.size} parts (Total: $totalSizeStr)." else "'$cleanTitle' (Total: $totalSizeStr)."
            UITheme.applyMetadataStyle(this)
            setPadding(0, 4, 0, 14)
        }
        cardList.addView(subHeaderText)

        var reverseOrder = false
        val reverseCheckBox = android.widget.CheckBox(activity).apply {
            text = "Reverse Part Order (Use if combined video fails to play)"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, isChecked -> reverseOrder = isChecked }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, UITheme.dpToPx(activity, 12))
            }
        }
        cardList.addView(reverseCheckBox)

        var dialog: AlertDialog? = null

        // Option 1: Download & Combine All Parts
        val optCombine = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = UITheme.createRippleCardShape(activity, UITheme.SURFACE, 14, UITheme.PRIMARY)
            val p = UITheme.dpToPx(activity, 12)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, UITheme.dpToPx(activity, 10))
            }
            isClickable = true
            setOnClickListener {
                dialog?.dismiss()
                val activeParts = if (reverseOrder) finalParts.reversed() else finalParts
                DownloadManager.startMultiPartDownload(activity, cleanTitle, originalFileName.ifBlank { cleanTitle }, activeParts, posterUrl)
                Toast.makeText(activity, "Started downloading & combining '$cleanTitle' 📥", Toast.LENGTH_SHORT).show()
            }
        }

        val icon1 = TextView(activity).apply { text = "⚡"; textSize = 18f; setPadding(0, 0, 12, 0) }
        optCombine.addView(icon1)

        val info1 = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val t1 = TextView(activity).apply { text = if (isZip) "Extract / Save as 1 Video File" else "Combine & Save as 1 Video File"; UITheme.applyCardTitleStyle(this); textSize = 14f; setTextColor(Color.WHITE) }
        val s1 = TextView(activity).apply { text = if (finalParts.size > 1) "Downloads all ${finalParts.size} parts and merges into single file ($totalSizeStr)" else "Downloads and saves as single video file ($totalSizeStr)"; UITheme.applyMetadataStyle(this); setTextColor(Color.parseColor(UITheme.PRIMARY)) }
        info1.addView(t1); info1.addView(s1)
        optCombine.addView(info1)
        cardList.addView(optCombine)

        // Option 2: Download All Parts Separately
        val optSep = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = UITheme.createRippleCardShape(activity, UITheme.CARD, 14, UITheme.STROKE_COLOR)
            val p = UITheme.dpToPx(activity, 12)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, UITheme.dpToPx(activity, 14))
            }
            isClickable = true
            setOnClickListener {
                dialog?.dismiss()
                finalParts.forEachIndexed { idx, part ->
                    val partTitle = "${cleanTitle} (Part ${idx + 1})"
                    val fileName = DownloadManager.sanitizeFileName(part.fileName.ifBlank { "${cleanTitle}_part${idx + 1}" })
                    DownloadManager.startDownload(activity, partTitle, fileName, part.fileId, part.chatId, part.messageId, posterUrl, part.fileSize)
                }
                Toast.makeText(activity, "Started downloading ${finalParts.size} separate parts 📥", Toast.LENGTH_SHORT).show()
            }
        }

        val icon2 = TextView(activity).apply { text = "📂"; textSize = 18f; setPadding(0, 0, 12, 0) }
        optSep.addView(icon2)

        val info2 = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val t2 = TextView(activity).apply { text = "Download All Parts Separately"; UITheme.applyCardTitleStyle(this); textSize = 14f; setTextColor(Color.WHITE) }
        val s2 = TextView(activity).apply { text = "Saves ${finalParts.size} individual files to your Downloads folder"; UITheme.applyMetadataStyle(this); setTextColor(Color.parseColor(UITheme.TEXT_SECONDARY)) }
        info2.addView(t2); info2.addView(s2)
        optSep.addView(info2)
        cardList.addView(optSep)

        // Parts Header
        val partsTitle = TextView(activity).apply {
            text = "Or Select Specific Part to Download:"
            UITheme.applyCardTitleStyle(this)
            textSize = 13f
            setPadding(0, 0, 0, 8)
        }
        cardList.addView(partsTitle)

        finalParts.forEachIndexed { index, part ->
            val partTitle = part.fileName.ifBlank { "Part ${index + 1}" }
            val partSize = TdlibManager.formatBytes(part.fileSize)

            val partCard = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = UITheme.createRippleCardShape(activity, UITheme.CARD, 12, UITheme.STROKE_COLOR)
                val p = UITheme.dpToPx(activity, 10)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, UITheme.dpToPx(activity, 6))
                }
                isClickable = true
                setOnClickListener {
                    dialog?.dismiss()
                    val fileName = DownloadManager.sanitizeFileName(partTitle)
                    DownloadManager.startDownload(activity, "${cleanTitle} - ${partTitle}", fileName, part.fileId, part.chatId, part.messageId, posterUrl, part.fileSize)
                    Toast.makeText(activity, "Started downloading ${partTitle} 📥", Toast.LENGTH_SHORT).show()
                }
            }

            val iconText = TextView(activity).apply {
                text = "📥 Part ${index + 1}"
                UITheme.applyCardTitleStyle(this)
                textSize = 13f
                setTextColor(Color.parseColor(UITheme.PRIMARY))
                setPadding(0, 0, 12, 0)
            }
            partCard.addView(iconText)

            val partInfo = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val pTitle = TextView(activity).apply {
                text = partTitle
                UITheme.applyCardTitleStyle(this)
                textSize = 13f
            }
            partInfo.addView(pTitle)

            val pSub = TextView(activity).apply {
                text = "Size: $partSize"
                UITheme.applyMetadataStyle(this)
                textSize = 11f
            }
            partInfo.addView(pSub)

            partCard.addView(partInfo)
            cardList.addView(partCard)
        }

        scrollView.addView(cardList)

        dialog = AlertDialog.Builder(activity)
            .setView(scrollView)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
        val metrics = activity.resources.displayMetrics
        val w = (metrics.widthPixels * 0.92).toInt()
        val h = (metrics.heightPixels * 0.82).toInt()
        dialog.window?.setLayout(w, h)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor(UITheme.BACKGROUND)))
    }
}
