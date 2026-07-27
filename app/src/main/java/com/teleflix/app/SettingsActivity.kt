package com.teleflix.app

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var authBtn: Button
    private lateinit var channelContainer: LinearLayout
    private lateinit var apiIdInput: EditText
    private lateinit var apiHashInput: EditText
    private lateinit var apiBox: LinearLayout
    private var authJob: Job? = null
    private var currentDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#090A0F"))
            fitsSystemWindows = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        scrollView.addView(root)

        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(
                24 + insets.left,
                24 + insets.top,
                24 + insets.right,
                24 + insets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }

        // Header
        val header = TextView(this).apply {
            text = "TELEGRAM & TDLIB SETTINGS"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#3B82F6"))
            setPadding(0, 0, 0, 24)
        }
        root.addView(header)

        fun createCollapsibleSection(titleText: String): LinearLayout {
            val headerCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.parseColor("#1F2937"))
                setPadding(32, 28, 32, 28)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, 16)
                }
            }

            val title = TextView(this).apply {
                text = titleText
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            headerCard.addView(title)

            val arrow = TextView(this).apply {
                text = "▼"
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#9CA3AF"))
            }
            headerCard.addView(arrow)
            root.addView(headerCard)

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = android.view.View.GONE
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, 24)
                }
            }
            root.addView(container)

            headerCard.setOnClickListener {
                if (container.visibility == android.view.View.VISIBLE) {
                    container.visibility = android.view.View.GONE
                    arrow.text = "▼"
                } else {
                    container.visibility = android.view.View.VISIBLE
                    arrow.text = "▲"
                }
            }
            return container
        }

        // 1. Teleflix Login Section
        val loginSettingsContainer = createCollapsibleSection("🔐 Teleflix Login & Account")

        // Session Status Box
        val sessionBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#161B28"))
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 16)
            }
        }

        val sessionTitle = TextView(this).apply {
            text = "Telegram Account Session"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        sessionBox.addView(sessionTitle)

        statusText = TextView(this).apply {
            text = "Initializing TDLib native engine..."
            textSize = 14f
            setTextColor(Color.parseColor("#F59E0B"))
            setPadding(0, 8, 0, 16)
        }
        sessionBox.addView(statusText)

        authBtn = Button(this).apply {
            text = "Connect Telegram Account"
            setBackgroundColor(Color.parseColor("#3B82F6"))
            setTextColor(Color.WHITE)
            setOnClickListener { handleAuthAction() }
        }
        sessionBox.addView(authBtn)

        loginSettingsContainer.addView(sessionBox)

        // API Credentials Box
        apiBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#161B28"))
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val apiTitle = TextView(this).apply {
            text = "TDLib API Credentials"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        apiBox.addView(apiTitle)

        apiIdInput = EditText(this).apply {
            hint = "API ID (Default: 2040012)"
            setHintTextColor(Color.parseColor("#6B7280"))
            setTextColor(Color.WHITE)
            setText(TdlibManager.getApiId(this@SettingsActivity).toString())
            setPadding(16, 12, 16, 12)
        }
        apiBox.addView(apiIdInput)

        apiHashInput = EditText(this).apply {
            hint = "API Hash"
            setHintTextColor(Color.parseColor("#6B7280"))
            setTextColor(Color.WHITE)
            setText(TdlibManager.getApiHash(this@SettingsActivity))
            setPadding(16, 12, 16, 12)
        }
        apiBox.addView(apiHashInput)

        val saveApiBtn = Button(this).apply {
            text = "Save Credentials & Reload TDLib"
            setBackgroundColor(Color.parseColor("#10B981"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val id = apiIdInput.text.toString().toIntOrNull() ?: 2040012
                val hash = apiHashInput.text.toString()
                TdlibManager.saveApiId(this@SettingsActivity, id)
                TdlibManager.saveApiHash(this@SettingsActivity, hash)
                TelegramClient.reset()
                TelegramClient.initialize(this@SettingsActivity)
                Toast.makeText(this@SettingsActivity, "Credentials Saved & TDLib Reloaded!", Toast.LENGTH_SHORT).show()
            }
        }
        apiBox.addView(saveApiBtn)

        loginSettingsContainer.addView(apiBox)

        // 2. Catalogue Channels Section (Collapsible)
        val channelsSectionContainer = createCollapsibleSection("📡 Catalogue Monitored Channels")

        val channelsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#161B28"))
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val channelDesc = TextView(this).apply {
            text = "Manage Telegram channels to show in your catalog:"
            textSize = 14f
            setTextColor(Color.parseColor("#93C5FD"))
            setPadding(0, 0, 0, 12)
        }
        channelsCard.addView(channelDesc)

        val addLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }

        val channelInput = EditText(this).apply {
            hint = "Enter @channel_name..."
            setHintTextColor(Color.parseColor("#6B7280"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#12151F"))
            setPadding(20, 16, 20, 16)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val addBtn = Button(this).apply {
            text = "Add"
            setBackgroundColor(Color.parseColor("#E50914"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (channelInput.text.isNotBlank()) {
                    TdlibManager.addChannel(this@SettingsActivity, channelInput.text.toString())
                    channelInput.setText("")
                    loadChannels()
                }
            }
        }

        addLayout.addView(channelInput)
        addLayout.addView(addBtn)
        channelsCard.addView(addLayout)

        channelContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        channelsCard.addView(channelContainer)
        channelsSectionContainer.addView(channelsCard)

        // 3. Video Playback & Preferred Player Section (Collapsible)
        val playbackSectionContainer = createCollapsibleSection("🎬 Video Playback & Internal Player")

        val playerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#161B28"))
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val playerTitle = TextView(this).apply {
            text = "Preferred Video Player"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        playerBox.addView(playerTitle)

        val prefs = getSharedPreferences("teleflix_preferences", android.content.Context.MODE_PRIVATE)
        val currentDefault = prefs.getString("default_player", "ask") ?: "ask"
        val playerMap = mapOf(
            "ask" to "Always Ask (Select Player on Tap)",
            "internal_mpv" to "🟣 Internal Player (Built-in MPV Engine)",
            "exo" to "🟣 Internal Player (Built-in MPV Engine)",
            "internal" to "🟣 Internal Player (Built-in MPV Engine)",
            "mpvex" to "🟢 MPVEX Player (External App)",
            "mpv" to "🔵 MPV Player (External App)",
            "vlc" to "🧡 VLC Player",
            "chooser" to "📱 Android System Player Chooser"
        )
        val playerDesc = TextView(this).apply {
            text = "Current Preferred Player:\n${playerMap[currentDefault] ?: "Always Ask"}"
            textSize = 14f
            setTextColor(Color.parseColor("#93C5FD"))
            setPadding(0, 12, 0, 12)
        }
        playerBox.addView(playerDesc)

        val changePlayerBtn = Button(this).apply {
            text = "Select Default Player (Internal MPV / MPVEX / VLC)"
            setBackgroundColor(Color.parseColor("#3B82F6"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val labels = arrayOf(
                    "Always Ask (Select Player on Tap)",
                    "🟣 Internal Player (Built-in MPV Engine)",
                    "🟢 MPVEX Player (External App)",
                    "🔵 MPV Player (External App)",
                    "🧡 VLC Player",
                    "📱 Android System Player Chooser"
                )
                val keys = arrayOf("ask", "internal_mpv", "mpvex", "mpv", "vlc", "chooser")
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Select Preferred Video Player")
                    .setItems(labels) { _, which ->
                        val selectedKey = keys[which]
                        val selectedLabel = labels[which]
                        prefs.edit().putString("default_player", selectedKey).apply()
                        playerDesc.text = "Current Preferred Player:\n$selectedLabel"
                        Toast.makeText(this@SettingsActivity, "Default player set to: $selectedLabel", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
        }
        playerBox.addView(changePlayerBtn)
        playbackSectionContainer.addView(playerBox)

        // 4. Background Service Section (Collapsible)
        val bgSectionContainer = createCollapsibleSection("⚡ Background Service & Execution")
        val bgBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#161B28"))
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val bgTitle = TextView(this).apply {
            text = "Run in Background Option"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        bgBox.addView(bgTitle)

        val bgDesc = TextView(this).apply {
            text = "When enabled, Teleflix keeps a persistent background service running for faster media streaming & notifications. Turn this OFF if you want Teleflix to completely shut down when you exit the app."
            textSize = 14f
            setTextColor(Color.parseColor("#93C5FD"))
            setPadding(0, 12, 0, 16)
        }
        bgBox.addView(bgDesc)

        var isBackgroundEnabled = prefs.getBoolean("pref_run_in_background", true)
        val bgStatusText = TextView(this).apply {
            text = if (isBackgroundEnabled) "Status: Enabled (Active in background)" else "Status: Disabled (Stops when closed)"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (isBackgroundEnabled) Color.parseColor("#10B981") else Color.parseColor("#EF4444"))
            setPadding(0, 0, 0, 16)
        }
        bgBox.addView(bgStatusText)

        val bgToggleButton = Button(this).apply {
            text = if (isBackgroundEnabled) "❌ TURN OFF RUN IN BACKGROUND" else "✅ TURN ON RUN IN BACKGROUND"
            setBackgroundColor(if (isBackgroundEnabled) Color.parseColor("#DC2626") else Color.parseColor("#059669"))
            setTextColor(Color.WHITE)
            setPadding(24, 16, 24, 16)
            setOnClickListener {
                isBackgroundEnabled = !isBackgroundEnabled
                prefs.edit().putBoolean("pref_run_in_background", isBackgroundEnabled).apply()
                if (isBackgroundEnabled) {
                    TelegramService.start(this@SettingsActivity)
                    text = "❌ TURN OFF RUN IN BACKGROUND"
                    setBackgroundColor(Color.parseColor("#DC2626"))
                    bgStatusText.text = "Status: Enabled (Active in background)"
                    bgStatusText.setTextColor(Color.parseColor("#10B981"))
                    Toast.makeText(this@SettingsActivity, "Run in background Turned ON & Started", Toast.LENGTH_SHORT).show()
                } else {
                    TelegramService.stop(this@SettingsActivity)
                    text = "✅ TURN ON RUN IN BACKGROUND"
                    setBackgroundColor(Color.parseColor("#059669"))
                    bgStatusText.text = "Status: Disabled (Stops when closed)"
                    bgStatusText.setTextColor(Color.parseColor("#EF4444"))
                    Toast.makeText(this@SettingsActivity, "Run in background Turned OFF & Stopped", Toast.LENGTH_SHORT).show()
                }
            }
        }
        bgBox.addView(bgToggleButton)
        bgSectionContainer.addView(bgBox)

        // 5. Storage, Cache & History Section (Collapsible)
        val storageSectionContainer = createCollapsibleSection("💾 Storage, Video Cache & Watch History")

        val storageBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#161B28"))
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val storageTitle = TextView(this).apply {
            text = "Streaming Buffer & Storage Management"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        storageBox.addView(storageTitle)

        val bufferDesc = TextView(this).apply {
            text = "Video Pre-fetch Buffer Size (Current: ${TelegramRepository.getBufferSizeMb(this@SettingsActivity)} MB)"
            textSize = 14f
            setTextColor(Color.parseColor("#93C5FD"))
            setPadding(0, 12, 0, 8)
        }
        storageBox.addView(bufferDesc)

        val bufferBtn = Button(this).apply {
            text = "Change Buffer Size (5MB - 100MB)"
            setBackgroundColor(Color.parseColor("#1E3A8A"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val sizes = arrayOf("5 MB (Low RAM)", "10 MB", "20 MB (Default)", "50 MB (Smooth 4K)", "100 MB (Ultra Smooth)")
                val values = arrayOf(5L, 10L, 20L, 50L, 100L)
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Select Video Buffer Size")
                    .setItems(sizes) { _, which ->
                        val selected = values[which]
                        TelegramRepository.saveBufferSizeMb(this@SettingsActivity, selected)
                        TelegramStreamingProxy.prefetchSizeMb = selected
                        bufferDesc.text = "Video Pre-fetch Buffer Size (Current: $selected MB)"
                        Toast.makeText(this@SettingsActivity, "Buffer set to $selected MB", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
        }
        storageBox.addView(bufferBtn)

        val cacheDesc = TextView(this).apply {
            text = "Calculating video cache size..."
            textSize = 14f
            setTextColor(Color.parseColor("#9CA3AF"))
            setPadding(0, 16, 0, 8)
        }
        storageBox.addView(cacheDesc)

        val clearCacheBtn = Button(this).apply {
            text = "Clear Streamed Video Cache"
            setBackgroundColor(Color.parseColor("#EF4444"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                TelegramRepository.clearCache(this@SettingsActivity)
                cacheDesc.text = "Video Cache Size: 0.0 MB (Cleared)"
                Toast.makeText(this@SettingsActivity, "Video cache cleared successfully!", Toast.LENGTH_SHORT).show()
            }
        }
        storageBox.addView(clearCacheBtn)

        val clearHistoryBtn = Button(this).apply {
            text = "Clear All Watch History & Resume Points"
            setBackgroundColor(Color.parseColor("#B91C1C"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Clear Watch History & Resume Points?")
                    .setMessage("This will permanently remove all saved movies, series, Telegram videos from Watch History, and clear saved video playback positions.")
                    .setPositiveButton("Clear All") { _, _ ->
                        getSharedPreferences("teleflix_watch_history", android.content.Context.MODE_PRIVATE).edit().clear().apply()
                        getSharedPreferences("TeleflixResume", android.content.Context.MODE_PRIVATE).edit().clear().apply()
                        Toast.makeText(this@SettingsActivity, "Watch History & Resume Points Cleared!", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        storageBox.addView(clearHistoryBtn)
        storageSectionContainer.addView(storageBox)

        setContentView(scrollView)

        CoroutineScope(Dispatchers.IO).launch {
            val sizeBytes = try { TelegramRepository.getCacheSize(this@SettingsActivity) } catch (_: Exception) { 0L }
            val sizeMb = sizeBytes / (1024.0 * 1024.0)
            withContext(Dispatchers.Main) {
                try { cacheDesc.text = String.format("Video Cache Size: %.1f MB", sizeMb) } catch (_: Exception) {}
            }
        }

        loadChannels()
    }

    override fun onStart() {
        super.onStart()
        TelegramClient.initialize(this)
        observeAuthState()
    }

    override fun onStop() {
        super.onStop()
        authJob?.cancel()
        currentDialog?.dismiss()
    }

    private fun observeAuthState() {
        authJob?.cancel()
        authJob = CoroutineScope(Dispatchers.Main).launch {
            TelegramClient.authState.collect { state ->
                if (::apiBox.isInitialized) {
                    val hideApiCredentials = state is TelegramAuthState.WaitCode ||
                            state is TelegramAuthState.WaitPassword ||
                            state is TelegramAuthState.Ready
                    apiBox.visibility = if (hideApiCredentials) android.view.View.GONE else android.view.View.VISIBLE
                }
                when (state) {
                    is TelegramAuthState.Idle -> {
                        statusText.text = "Idle (Not connected)"
                        statusText.setTextColor(Color.parseColor("#9CA3AF"))
                        authBtn.text = "Initialize TDLib"
                    }
                    is TelegramAuthState.Initializing -> {
                        statusText.text = "Initializing native TDLib client..."
                        statusText.setTextColor(Color.parseColor("#F59E0B"))
                        authBtn.text = "Please Wait..."
                    }
                    is TelegramAuthState.WaitPhone -> {
                        statusText.text = "Ready to Login (Phone Number Required)"
                        statusText.setTextColor(Color.parseColor("#EF4444"))
                        authBtn.text = "Enter Phone Number"
                    }
                    is TelegramAuthState.WaitCode -> {
                        statusText.text = "Verification code sent! (Awaiting Code)"
                        statusText.setTextColor(Color.parseColor("#F59E0B"))
                        authBtn.text = "Enter Verification Code"
                        showCodeDialog(state.codeLength)
                    }
                    is TelegramAuthState.WaitPassword -> {
                        statusText.text = "2FA Enabled (Password Required)"
                        statusText.setTextColor(Color.parseColor("#F59E0B"))
                        authBtn.text = "Enter 2FA Password"
                        showPasswordDialog()
                    }
                    is TelegramAuthState.Ready -> {
                        statusText.text = "Connected as ${state.firstName} (ID: ${state.userId})\nNative TDLib active and monitoring channels!"
                        statusText.setTextColor(Color.parseColor("#10B981"))
                        authBtn.text = "Log Out of Telegram"
                    }
                    is TelegramAuthState.Error -> {
                        statusText.text = "Error: ${state.message}"
                        statusText.setTextColor(Color.parseColor("#EF4444"))
                        authBtn.text = "Retry Connection"
                    }
                    else -> {}
                }
            }
        }
    }

    private fun handleAuthAction() {
        when (val state = TelegramClient.authState.value) {
            is TelegramAuthState.Idle,
            is TelegramAuthState.Initializing,
            is TelegramAuthState.Error -> {
                TelegramClient.initialize(this)
            }
            is TelegramAuthState.WaitPhone -> {
                showPhoneDialog()
            }
            is TelegramAuthState.WaitCode -> {
                showCodeDialog(state.codeLength)
            }
            is TelegramAuthState.WaitPassword -> {
                showPasswordDialog()
            }
            is TelegramAuthState.Ready -> {
                AlertDialog.Builder(this)
                    .setTitle("Log Out of Telegram?")
                    .setMessage("This will disconnect your account from TDLib and clear local session data.")
                    .setPositiveButton("Log Out") { _, _ ->
                        TelegramClient.logout(this)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            else -> {
                showPhoneDialog()
            }
        }
    }

    private fun showPhoneDialog() {
        currentDialog?.dismiss()
        val phoneInput = EditText(this).apply {
            hint = "+1 (555) 019-2834"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(TdlibManager.getUserPhone(this@SettingsActivity))
            setSelection(text.length)
        }

        currentDialog = AlertDialog.Builder(this)
            .setTitle("Connect Telegram")
            .setMessage("Enter your full international phone number starting with + and country code:")
            .setView(phoneInput)
            .setPositiveButton("Send Code") { _, _ ->
                val phone = phoneInput.text.toString().trim()
                if (phone.isNotBlank()) {
                    Toast.makeText(this, "Requesting Telegram OTP for $phone...", Toast.LENGTH_SHORT).show()
                    TelegramClient.submitPhone(phone)
                } else {
                    Toast.makeText(this, "Please enter a valid phone number", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCodeDialog(codeLength: Int) {
        currentDialog?.dismiss()
        val codeInput = EditText(this).apply {
            hint = "e.g. 12345 (length: $codeLength)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        currentDialog = AlertDialog.Builder(this)
            .setTitle("Enter Verification Code")
            .setMessage("Please enter the verification code sent to your Telegram app or SMS:")
            .setView(codeInput)
            .setPositiveButton("Submit Code") { _, _ ->
                val code = codeInput.text.toString().trim()
                if (code.isNotBlank()) {
                    Toast.makeText(this, "Verifying code...", Toast.LENGTH_SHORT).show()
                    TelegramClient.submitCode(code)
                }
            }
            .setNeutralButton("⬅️ Back") { _, _ ->
                TelegramClient.logout(this)
                TelegramClient.reset()
                TelegramClient.initialize(this)
                showPhoneDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPasswordDialog() {
        currentDialog?.dismiss()
        val passwordInput = EditText(this).apply {
            hint = "2FA Cloud Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        currentDialog = AlertDialog.Builder(this)
            .setTitle("Two-Step Verification")
            .setMessage("Your Telegram account is protected by a password. Please enter it below:")
            .setView(passwordInput)
            .setPositiveButton("Submit Password") { _, _ ->
                val password = passwordInput.text.toString()
                Toast.makeText(this, "Verifying password...", Toast.LENGTH_SHORT).show()
                TelegramClient.submitPassword(password)
            }
            .setNeutralButton("⬅️ Back") { _, _ ->
                TelegramClient.logout(this)
                TelegramClient.reset()
                TelegramClient.initialize(this)
                showPhoneDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadChannels() {
        channelContainer.removeAllViews()
        val list = TdlibManager.getChannels(this)
        if (list.isEmpty()) {
            val emptyMsg = TextView(this).apply {
                text = "No Telegram channels monitored yet.\nEnter a channel username above (e.g. @your_channel) and press ADD!"
                setTextColor(Color.parseColor("#6B7280"))
                textSize = 13f
                setPadding(8, 16, 8, 16)
            }
            channelContainer.addView(emptyMsg)
            return
        }
        list.forEach { ch ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.parseColor("#12151F"))
                setPadding(16, 12, 16, 12)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 4, 0, 4)
                }
            }

            val name = TextView(this).apply {
                text = "${ch.username} (${ch.title})"
                setTextColor(Color.WHITE)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val removeBtn = Button(this).apply {
                text = "Remove"
                setBackgroundColor(Color.parseColor("#7F1D1D"))
                setTextColor(Color.WHITE)
                textSize = 11f
                setOnClickListener {
                    TdlibManager.removeChannel(this@SettingsActivity, ch.username)
                    loadChannels()
                }
            }

            row.addView(name)
            row.addView(removeBtn)
            channelContainer.addView(row)
        }
    }
}
