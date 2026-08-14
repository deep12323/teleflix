package com.teleflix.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var authBtn: Button
    private lateinit var apiIdInput: EditText
    private lateinit var apiHashInput: EditText
    private lateinit var apiBox: LinearLayout
    private lateinit var cacheSub: TextView
    private var authJob: Job? = null
    private var currentDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(UITheme.BACKGROUND))
            fitsSystemWindows = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = UITheme.dpToPx(this@SettingsActivity, 16)
            setPadding(pad, pad, pad, pad)
        }
        scrollView.addView(root)

        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val pad = UITheme.dpToPx(this@SettingsActivity, 16)
            view.setPadding(
                pad + insets.left,
                pad + insets.top,
                pad + insets.right,
                pad + insets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }

        // Header
        val header = TextView(this).apply {
            text = "TELEGRAM & TDLIB SETTINGS"
            UITheme.applyLargeTitleStyle(this)
            setTextColor(Color.parseColor(UITheme.PRIMARY))
            setPadding(0, 0, 0, UITheme.dpToPx(this@SettingsActivity, 16))
        }
        root.addView(header)

        fun createCollapsibleSection(titleText: String): LinearLayout {
            val headerCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = UITheme.createRippleCardShape(this@SettingsActivity, UITheme.SURFACE, 16, UITheme.STROKE_COLOR)
                val pV = UITheme.dpToPx(this@SettingsActivity, 14)
                val pH = UITheme.dpToPx(this@SettingsActivity, 16)
                setPadding(pH, pV, pH, pV)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, UITheme.dpToPx(this@SettingsActivity, 12))
                }
            }

            val title = TextView(this).apply {
                text = titleText
                UITheme.applySectionTitleStyle(this)
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            headerCard.addView(title)

            val arrow = TextView(this).apply {
                text = "▼"
                textSize = 14f
                setTypeface(null, Typeface.NORMAL)
                setTextColor(Color.parseColor(UITheme.TEXT_SECONDARY))
            }
            headerCard.addView(arrow)
            root.addView(headerCard)

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = android.view.View.GONE
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, UITheme.dpToPx(this@SettingsActivity, 16))
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

        val isConfigured = TdlibManager.isApiCredentialsConfigured(this)

        val apiTitle = TextView(this).apply {
            text = "🔐 TDLib API Credentials (my.telegram.org)"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        apiBox.addView(apiTitle)

        val apiStatusText = TextView(this).apply {
            text = if (isConfigured) "🟢 API Credentials Configured" else "⚠️ API Credentials Required (Set up from my.telegram.org)"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(if (isConfigured) "#10B981" else "#F59E0B"))
            setPadding(0, 4, 0, 8)
        }
        apiBox.addView(apiStatusText)

        val apiDesc = TextView(this).apply {
            text = "To connect Telegram, enter your free API ID & Hash from my.telegram.org:\n1. Open https://my.telegram.org in your browser\n2. Log in with your phone number\n3. Click 'API development tools' and create an application\n4. Copy your App api_id and App api_hash below:"
            textSize = 12f
            setTextColor(Color.parseColor("#9CA3AF"))
            setPadding(0, 0, 0, 10)
        }
        apiBox.addView(apiDesc)

        val currentApiId = TdlibManager.getApiId(this)
        apiIdInput = EditText(this).apply {
            hint = "App api_id (e.g. 12345678)"
            setHintTextColor(Color.parseColor("#6B7280"))
            setTextColor(Color.WHITE)
            if (currentApiId > 0) {
                setText(currentApiId.toString())
            }
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(16, 12, 16, 12)
        }
        apiBox.addView(apiIdInput)

        apiHashInput = EditText(this).apply {
            hint = "App api_hash (32-character hex)"
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
                val id = apiIdInput.text.toString().trim().toIntOrNull()
                val hash = apiHashInput.text.toString().trim()
                if (id == null || id <= 0 || hash.isBlank()) {
                    Toast.makeText(this@SettingsActivity, "Please enter a valid API ID and API Hash from my.telegram.org", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                TdlibManager.saveApiId(this@SettingsActivity, id)
                TdlibManager.saveApiHash(this@SettingsActivity, hash)
                TelegramClient.reset()
                TelegramClient.initialize(this@SettingsActivity)
                apiStatusText.text = "🟢 API Credentials Configured"
                apiStatusText.setTextColor(Color.parseColor("#10B981"))
                Toast.makeText(this@SettingsActivity, "Credentials Saved & TDLib Reloaded!", Toast.LENGTH_SHORT).show()
            }
        }
        apiBox.addView(saveApiBtn)

        loginSettingsContainer.addView(apiBox)

        // 2. Download Location & Storage Section (Collapsible)
        val downloadSettingsContainer = createCollapsibleSection("📥 Download Location & Storage")

        val downloadBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UITheme.createCardShape(this@SettingsActivity, UITheme.CARD, 16, UITheme.STROKE_COLOR, 1)
            val pad = UITheme.dpToPx(this@SettingsActivity, 16)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val dlTitle = TextView(this).apply {
            text = "Video Download Save Path"
            UITheme.applyCardTitleStyle(this)
            textSize = 15f
        }
        downloadBox.addView(dlTitle)

        val activePathSub = TextView(this).apply {
            UITheme.applyMetadataStyle(this)
            setPadding(0, UITheme.dpToPx(this@SettingsActivity, 4), 0, UITheme.dpToPx(this@SettingsActivity, 12))
        }
        fun updateActivePathText() {
            activePathSub.text = "Current Save Path:\n${DownloadManager.getFormattedActivePath(this@SettingsActivity)}"
        }
        updateActivePathText()
        downloadBox.addView(activePathSub)

        val curMode = DownloadManager.getStorageMode(this)

        val optPublicStorage = TextView(this).apply {
            text = "📂 Public Downloads Directory (Default)\n/sdcard/Download/Teleflix"
            UITheme.applyCardTitleStyle(this)
            textSize = 13f
            val isCur = curMode != "custom"
            background = UITheme.createRippleCardShape(this@SettingsActivity, if (isCur) UITheme.SURFACE else UITheme.CARD, 14, if (isCur) UITheme.PRIMARY else UITheme.STROKE_COLOR)
            setTextColor(if (isCur) Color.WHITE else Color.parseColor(UITheme.TEXT_SECONDARY))
            val pV = UITheme.dpToPx(this@SettingsActivity, 12)
            val pH = UITheme.dpToPx(this@SettingsActivity, 14)
            setPadding(pH, pV, pH, pV)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, UITheme.dpToPx(this@SettingsActivity, 8))
            }
        }

        val optCustomStorage = TextView(this).apply {
            text = "🛠️ Custom Directory Location\nEnter your own folder path"
            UITheme.applyCardTitleStyle(this)
            textSize = 13f
            val isCur = curMode == "custom"
            background = UITheme.createRippleCardShape(this@SettingsActivity, if (isCur) UITheme.SURFACE else UITheme.CARD, 14, if (isCur) UITheme.PRIMARY else UITheme.STROKE_COLOR)
            setTextColor(if (isCur) Color.WHITE else Color.parseColor(UITheme.TEXT_SECONDARY))
            val pV = UITheme.dpToPx(this@SettingsActivity, 12)
            val pH = UITheme.dpToPx(this@SettingsActivity, 14)
            setPadding(pH, pV, pH, pV)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, UITheme.dpToPx(this@SettingsActivity, 8))
            }
        }

        val customPathInputLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (curMode == "custom") android.view.View.VISIBLE else android.view.View.GONE
            setPadding(0, UITheme.dpToPx(this@SettingsActivity, 6), 0, 0)
        }

        val customPathEdit = EditText(this).apply {
            hint = "/storage/emulated/0/Movies/Teleflix"
            setHintTextColor(Color.parseColor("#6B7280"))
            setTextColor(Color.WHITE)
            setText(DownloadManager.getCustomPath(this@SettingsActivity))
            background = UITheme.createInputBackground(this@SettingsActivity)
            val pV = UITheme.dpToPx(this@SettingsActivity, 10)
            val pH = UITheme.dpToPx(this@SettingsActivity, 12)
            setPadding(pH, pV, pH, pV)
        }

        val saveCustomPathBtn = Button(this).apply {
            text = "Save Custom Storage Path"
            setBackgroundColor(Color.parseColor("#10B981"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val inputPath = customPathEdit.text.toString().trim()
                if (inputPath.isBlank()) {
                    Toast.makeText(this@SettingsActivity, "Please enter a valid path", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                DownloadManager.setCustomPath(this@SettingsActivity, inputPath)
                DownloadManager.setStorageMode(this@SettingsActivity, "custom")
                updateActivePathText()
                Toast.makeText(this@SettingsActivity, "Saved custom storage location!", Toast.LENGTH_SHORT).show()
            }
        }

        customPathInputLayout.addView(customPathEdit)
        customPathInputLayout.addView(saveCustomPathBtn)

        fun updateModeSelection(newMode: String) {
            DownloadManager.setStorageMode(this@SettingsActivity, newMode)
            updateActivePathText()

            val isPub = newMode != "custom"
            val isCust = newMode == "custom"

            optPublicStorage.background = UITheme.createRippleCardShape(this@SettingsActivity, if (isPub) UITheme.SURFACE else UITheme.CARD, 14, if (isPub) UITheme.PRIMARY else UITheme.STROKE_COLOR)
            optPublicStorage.setTextColor(if (isPub) Color.WHITE else Color.parseColor(UITheme.TEXT_SECONDARY))

            optCustomStorage.background = UITheme.createRippleCardShape(this@SettingsActivity, if (isCust) UITheme.SURFACE else UITheme.CARD, 14, if (isCust) UITheme.PRIMARY else UITheme.STROKE_COLOR)
            optCustomStorage.setTextColor(if (isCust) Color.WHITE else Color.parseColor(UITheme.TEXT_SECONDARY))

            customPathInputLayout.visibility = if (isCust) android.view.View.VISIBLE else android.view.View.GONE
        }

        optPublicStorage.setOnClickListener { updateModeSelection("public_downloads") }
        optCustomStorage.setOnClickListener { updateModeSelection("custom") }

        downloadBox.addView(optPublicStorage)
        downloadBox.addView(optCustomStorage)
        downloadBox.addView(customPathInputLayout)

        downloadSettingsContainer.addView(downloadBox)

        // 3. Video Playback & Preferred Player Section (Collapsible)
        val playbackSectionContainer = createCollapsibleSection("🎬 Video Playback & Startup")

        val playerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UITheme.createCardShape(this@SettingsActivity, UITheme.CARD, 16, UITheme.STROKE_COLOR, 1)
            val pad = UITheme.dpToPx(this@SettingsActivity, 16)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val prefs = getSharedPreferences("teleflix_preferences", android.content.Context.MODE_PRIVATE)

        // Preferred Player Row
        val playerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, UITheme.dpToPx(this@SettingsActivity, 8))
        }

        val playerInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val playerTitle = TextView(this).apply {
            text = "Preferred Video Player"
            UITheme.applyCardTitleStyle(this)
            textSize = 15f
        }
        playerInfo.addView(playerTitle)

        val playerMap = mapOf(
            "ask" to "Always Ask",
            "exo" to "⚡ ExoPlayer",
            "mpvex" to "🔴 MPVEX Player",
            "mpv" to "🔵 MPV Player",
            "vlc" to "🧡 VLC Player",
            "chooser" to "📱 System Chooser"
        )
        var currentDefault = prefs.getString("default_player", "ask") ?: "ask"
        val playerSub = TextView(this).apply {
            text = playerMap[currentDefault] ?: "Always Ask"
            UITheme.applyMetadataStyle(this)
            setTextColor(Color.parseColor(UITheme.ACCENT_BLUE))
        }
        playerInfo.addView(playerSub)
        playerRow.addView(playerInfo)

        val changePlayerBtn = Button(this).apply {
            text = "Select"
            textSize = 12f
            background = UITheme.createBadgeDrawable(this@SettingsActivity, UITheme.ACCENT_BLUE, 10)
            setTextColor(Color.WHITE)
            setOnClickListener {
                val labels = arrayOf(
                    "Always Ask (Select Player on Tap)",
                    "⚡ ExoPlayer (External App / Just Player)",
                    "🔴 MPVEX Player (app.marlboroadvance.mpvex)",
                    "🔵 MPV Player (is.xyz.mpv)",
                    "🧡 VLC Player",
                    "📱 Android System Player Chooser"
                )
                val keys = arrayOf("ask", "exo", "mpvex", "mpv", "vlc", "chooser")

                val scrollView = ScrollView(this@SettingsActivity).apply {
                    setBackgroundColor(Color.parseColor(UITheme.BACKGROUND))
                }
                val container = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = UITheme.dpToPx(this@SettingsActivity, 16)
                    setPadding(pad, pad, pad, pad)
                }

                val title = TextView(this@SettingsActivity).apply {
                    text = "Select Preferred Video Player"
                    UITheme.applySectionTitleStyle(this)
                    setTextColor(Color.WHITE)
                    setPadding(0, 0, 0, UITheme.dpToPx(this@SettingsActivity, 14))
                }
                container.addView(title)

                var dialog: AlertDialog? = null
                val curKey = prefs.getString("default_player", "ask") ?: "ask"

                for (i in labels.indices) {
                    val key = keys[i]
                    val isCur = key == curKey
                    val card = TextView(this@SettingsActivity).apply {
                        text = labels[i]
                        UITheme.applyCardTitleStyle(this)
                        background = UITheme.createRippleCardShape(
                            this@SettingsActivity,
                            if (isCur) UITheme.SURFACE else UITheme.CARD,
                            14,
                            if (isCur) UITheme.PRIMARY else UITheme.STROKE_COLOR
                        )
                        setTextColor(if (isCur) Color.WHITE else Color.parseColor(UITheme.TEXT_SECONDARY))
                        val pV = UITheme.dpToPx(this@SettingsActivity, 12)
                        val pH = UITheme.dpToPx(this@SettingsActivity, 16)
                        setPadding(pH, pV, pH, pV)
                        isClickable = true
                        isFocusable = true
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, 0, 0, UITheme.dpToPx(this@SettingsActivity, 8))
                        }
                        setOnClickListener {
                            dialog?.dismiss()
                            prefs.edit().putString("default_player", key).apply()
                            playerSub.text = playerMap[key] ?: labels[i]
                            Toast.makeText(this@SettingsActivity, "Default player updated", Toast.LENGTH_SHORT).show()
                        }
                    }
                    container.addView(card)
                }

                scrollView.addView(container)

                dialog = AlertDialog.Builder(this@SettingsActivity)
                    .setView(scrollView)
                    .setNegativeButton("Cancel", null)
                    .create()
                dialog.show()
            }
        }
        playerRow.addView(changePlayerBtn)
        playerBox.addView(playerRow)

        // Divider
        val playerDiv = android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, UITheme.dpToPx(this@SettingsActivity, 10), 0, UITheme.dpToPx(this@SettingsActivity, 10))
            }
            setBackgroundColor(Color.parseColor(UITheme.STROKE_COLOR))
        }
        playerBox.addView(playerDiv)

        // Auto-Resume Toggle Row
        val resumeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, UITheme.dpToPx(this@SettingsActivity, 4), 0, UITheme.dpToPx(this@SettingsActivity, 4))
        }

        val resumeInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val resumeTitle = TextView(this).apply {
            text = "Auto-Resume Playback"
            UITheme.applyCardTitleStyle(this)
            textSize = 15f
        }
        resumeInfo.addView(resumeTitle)

        val resumeSub = TextView(this).apply {
            text = "Automatically resume videos from last saved position"
            UITheme.applyMetadataStyle(this)
        }
        resumeInfo.addView(resumeSub)
        resumeRow.addView(resumeInfo)

        val resumeSwitch = Switch(this).apply {
            isChecked = prefs.getBoolean("always_resume", false)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("always_resume", isChecked).apply()
                val msg = if (isChecked) "Auto-resume turned ON" else "Auto-resume turned OFF"
                Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
        resumeRow.addView(resumeSwitch)
        playerBox.addView(resumeRow)

        // Divider 2
        val playerDiv2 = android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, UITheme.dpToPx(this@SettingsActivity, 10), 0, UITheme.dpToPx(this@SettingsActivity, 10))
            }
            setBackgroundColor(Color.parseColor(UITheme.STROKE_COLOR))
        }
        playerBox.addView(playerDiv2)

        // Default Opening Page Toggle Row
        val defaultPageRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, UITheme.dpToPx(this@SettingsActivity, 4), 0, UITheme.dpToPx(this@SettingsActivity, 4))
        }

        val defaultPageInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val defaultPageTitle = TextView(this).apply {
            text = "Monitored Channels as Default Page"
            UITheme.applyCardTitleStyle(this)
            textSize = 15f
        }
        defaultPageInfo.addView(defaultPageTitle)

        val defaultPageSub = TextView(this).apply {
            text = "Open Monitored Telegram Channels on app launch"
            UITheme.applyMetadataStyle(this)
        }
        defaultPageInfo.addView(defaultPageSub)
        defaultPageRow.addView(defaultPageInfo)

        val defaultPageSwitch = Switch(this).apply {
            isChecked = prefs.getBoolean("default_opening_monitored", false)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("default_opening_monitored", isChecked).apply()
                val msg = if (isChecked) "Monitored Channels set as default opening page" else "Cinemeta set as default opening page"
                Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_SHORT).show()
            }
        }
        defaultPageRow.addView(defaultPageSwitch)
        playerBox.addView(defaultPageRow)

        playbackSectionContainer.addView(playerBox)

        // 4. Background Service Section (Collapsible)
        val bgSectionContainer = createCollapsibleSection("⚡ Background Service & Execution")
        val bgBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UITheme.createCardShape(this@SettingsActivity, UITheme.CARD, 16, UITheme.STROKE_COLOR, 1)
            val pad = UITheme.dpToPx(this@SettingsActivity, 16)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val bgRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val bgInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val bgTitle = TextView(this).apply {
            text = "Run in Background"
            UITheme.applyCardTitleStyle(this)
            textSize = 15f
        }
        bgInfo.addView(bgTitle)

        val bgSub = TextView(this).apply {
            text = "Keep service active for faster streaming & notifications"
            UITheme.applyMetadataStyle(this)
        }
        bgInfo.addView(bgSub)
        bgRow.addView(bgInfo)

        val bgSwitch = Switch(this).apply {
            isChecked = prefs.getBoolean("pref_run_in_background", true)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("pref_run_in_background", isChecked).apply()
                if (isChecked) {
                    if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
                    } else {
                        TelegramService.start(this@SettingsActivity)
                    }
                    Toast.makeText(this@SettingsActivity, "Background service enabled", Toast.LENGTH_SHORT).show()
                } else {
                    TelegramService.stop(this@SettingsActivity)
                    Toast.makeText(this@SettingsActivity, "Background service disabled", Toast.LENGTH_SHORT).show()
                }
            }
        }
        bgRow.addView(bgSwitch)
        bgBox.addView(bgRow)
        bgSectionContainer.addView(bgBox)

        // 5. Storage, Cache & History Section (Collapsible)
        val storageSectionContainer = createCollapsibleSection("💾 App Cache, Storage & Watch History")

        val storageBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UITheme.createCardShape(this@SettingsActivity, UITheme.CARD, 16, UITheme.STROKE_COLOR, 1)
            val pad = UITheme.dpToPx(this@SettingsActivity, 16)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        // Buffer Row
        val bufferRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, UITheme.dpToPx(this@SettingsActivity, 8))
        }

        val bufferInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val bufferTitle = TextView(this).apply {
            text = "Pre-fetch RAM Buffer"
            UITheme.applyCardTitleStyle(this)
            textSize = 15f
        }
        bufferInfo.addView(bufferTitle)

        val currentBufMb = TelegramRepository.getBufferSizeMb(this)
        val bufDisplay = if (currentBufMb >= 1024L) "${currentBufMb / 1024} GB" else "$currentBufMb MB"
        val bufferSub = TextView(this).apply {
            text = "Current Buffer: $bufDisplay"
            UITheme.applyMetadataStyle(this)
            setTextColor(Color.parseColor(UITheme.ACCENT_BLUE))
        }
        bufferInfo.addView(bufferSub)
        bufferRow.addView(bufferInfo)

        val changeBufferBtn = Button(this).apply {
            text = "Change"
            textSize = 12f
            background = UITheme.createBadgeDrawable(this@SettingsActivity, UITheme.ACCENT_BLUE, 10)
            setTextColor(Color.WHITE)
            setOnClickListener {
                val sizes = arrayOf("5 MB (Low RAM)", "10 MB", "20 MB", "32 MB (Default)", "50 MB (Smooth 4K)", "100 MB (Ultra Smooth)", "100 GB (Full Buffer)")
                val values = arrayOf(5L, 10L, 20L, 32L, 50L, 100L, 102400L)

                val scrollView = ScrollView(this@SettingsActivity).apply {
                    setBackgroundColor(Color.parseColor(UITheme.BACKGROUND))
                }
                val container = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = UITheme.dpToPx(this@SettingsActivity, 16)
                    setPadding(pad, pad, pad, pad)
                }

                val title = TextView(this@SettingsActivity).apply {
                    text = "Select Pre-fetch RAM Buffer"
                    UITheme.applySectionTitleStyle(this)
                    setTextColor(Color.WHITE)
                    setPadding(0, 0, 0, UITheme.dpToPx(this@SettingsActivity, 14))
                }
                container.addView(title)

                var dialog: AlertDialog? = null
                val curBuf = TelegramRepository.getBufferSizeMb(this@SettingsActivity)

                for (i in sizes.indices) {
                    val valMb = values[i]
                    val isCur = valMb == curBuf
                    val card = TextView(this@SettingsActivity).apply {
                        text = sizes[i]
                        UITheme.applyCardTitleStyle(this)
                        background = UITheme.createRippleCardShape(
                            this@SettingsActivity,
                            if (isCur) UITheme.SURFACE else UITheme.CARD,
                            14,
                            if (isCur) UITheme.PRIMARY else UITheme.STROKE_COLOR
                        )
                        setTextColor(if (isCur) Color.WHITE else Color.parseColor(UITheme.TEXT_SECONDARY))
                        val pV = UITheme.dpToPx(this@SettingsActivity, 12)
                        val pH = UITheme.dpToPx(this@SettingsActivity, 16)
                        setPadding(pH, pV, pH, pV)
                        isClickable = true
                        isFocusable = true
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, 0, 0, UITheme.dpToPx(this@SettingsActivity, 8))
                        }
                        setOnClickListener {
                            dialog?.dismiss()
                            val selDisplay = if (valMb >= 1024L) "${valMb / 1024} GB" else "$valMb MB"
                            TelegramRepository.saveBufferSizeMb(this@SettingsActivity, valMb)
                            TelegramStreamingProxy.prefetchSizeMb = valMb
                            bufferSub.text = "Current Buffer: $selDisplay"
                            Toast.makeText(this@SettingsActivity, "Buffer set to $selDisplay", Toast.LENGTH_SHORT).show()
                        }
                    }
                    container.addView(card)
                }

                scrollView.addView(container)

                dialog = AlertDialog.Builder(this@SettingsActivity)
                    .setView(scrollView)
                    .setNegativeButton("Cancel", null)
                    .create()
                dialog.show()
            }
        }
        bufferRow.addView(changeBufferBtn)
        storageBox.addView(bufferRow)

        // Divider
        val storageDiv1 = android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, UITheme.dpToPx(this@SettingsActivity, 8), 0, UITheme.dpToPx(this@SettingsActivity, 8))
            }
            setBackgroundColor(Color.parseColor(UITheme.STROKE_COLOR))
        }
        storageBox.addView(storageDiv1)

        // App Cache Row
        val cacheRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, UITheme.dpToPx(this@SettingsActivity, 4), 0, UITheme.dpToPx(this@SettingsActivity, 4))
        }

        val cacheInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val cacheTitle = TextView(this).apply {
            text = "App Cache & Posters"
            UITheme.applyCardTitleStyle(this)
            textSize = 15f
        }
        cacheInfo.addView(cacheTitle)

        cacheSub = TextView(this).apply {
            text = "Calculating cache size..."
            UITheme.applyMetadataStyle(this)
        }
        cacheInfo.addView(cacheSub)
        cacheRow.addView(cacheInfo)

        val clearCacheBtn = Button(this).apply {
            text = "Clear"
            textSize = 12f
            background = UITheme.createBadgeDrawable(this@SettingsActivity, "#DC2626", 10)
            setTextColor(Color.WHITE)
            setOnClickListener {
                TelegramRepository.clearCache(this@SettingsActivity, clearPosters = true)
                CoroutineScope(Dispatchers.IO).launch {
                    try { com.bumptech.glide.Glide.get(this@SettingsActivity).clearDiskCache() } catch (_: Exception) {}
                    withContext(Dispatchers.Main) {
                        try { com.bumptech.glide.Glide.get(this@SettingsActivity).clearMemory() } catch (_: Exception) {}
                    }
                }
                cacheSub.text = "Cache: 0.0 MB (Cleared)"
                Toast.makeText(this@SettingsActivity, "All poster & thumbnail cache cleared!", Toast.LENGTH_SHORT).show()
            }
        }
        cacheRow.addView(clearCacheBtn)
        storageBox.addView(cacheRow)

        // Divider
        val div2 = android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, UITheme.dpToPx(this@SettingsActivity, 8), 0, UITheme.dpToPx(this@SettingsActivity, 8))
            }
            setBackgroundColor(Color.parseColor(UITheme.STROKE_COLOR))
        }
        storageBox.addView(div2)

        // Watch History Row
        val historyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, UITheme.dpToPx(this@SettingsActivity, 4), 0, UITheme.dpToPx(this@SettingsActivity, 4))
        }

        val historyInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val historyTitle = TextView(this).apply {
            text = "Watch History & Resume Points"
            UITheme.applyCardTitleStyle(this)
            textSize = 15f
        }
        historyInfo.addView(historyTitle)

        val historySub = TextView(this).apply {
            text = "Clear all saved playback positions"
            UITheme.applyMetadataStyle(this)
        }
        historyInfo.addView(historySub)
        historyRow.addView(historyInfo)

        val clearHistoryBtn = Button(this).apply {
            text = "Clear All"
            textSize = 12f
            background = UITheme.createBadgeDrawable(this@SettingsActivity, "#991B1B", 10)
            setTextColor(Color.WHITE)
            setOnClickListener {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Clear Watch History & Resume Points?")
                    .setMessage("This will permanently remove all saved movies, series, Telegram videos from Watch History, and clear saved video playback positions.")
                    .setPositiveButton("Clear All") { _, _ ->
                        getSharedPreferences("teleflix_watch_history", android.content.Context.MODE_PRIVATE).edit().clear().apply()
                        getSharedPreferences("teleflix_resume_points", android.content.Context.MODE_PRIVATE).edit().clear().apply()
                        getSharedPreferences("TeleflixResume", android.content.Context.MODE_PRIVATE).edit().clear().apply()
                        Toast.makeText(this@SettingsActivity, "Watch History & Resume Points Cleared!", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        historyRow.addView(clearHistoryBtn)
        storageBox.addView(historyRow)
        storageSectionContainer.addView(storageBox)

        // 6. Diagnostic & Streaming Logs Section (Collapsible)
        val logsSectionContainer = createCollapsibleSection("📋 Diagnostic & Streaming Logs")

        val logsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UITheme.createCardShape(this@SettingsActivity, UITheme.CARD, 16, UITheme.STROKE_COLOR, 1)
            val pad = UITheme.dpToPx(this@SettingsActivity, 16)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        // View Logs Row
        val viewLogsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, UITheme.dpToPx(this@SettingsActivity, 8))
        }

        val viewLogsInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val viewLogsTitle = TextView(this).apply {
            text = "Real-Time Streaming Logs"
            UITheme.applyCardTitleStyle(this)
            textSize = 15f
        }
        viewLogsInfo.addView(viewLogsTitle)

        val viewLogsSub = TextView(this).apply {
            text = "Inspect live proxy & TDLib range requests"
            UITheme.applyMetadataStyle(this)
        }
        viewLogsInfo.addView(viewLogsSub)
        viewLogsRow.addView(viewLogsInfo)

        val viewLogsBtn = Button(this).apply {
            text = "View"
            textSize = 12f
            background = UITheme.createBadgeDrawable(this@SettingsActivity, UITheme.ACCENT_BLUE, 10)
            setTextColor(Color.WHITE)
            setOnClickListener {
                showLogsViewerDialog()
            }
        }
        viewLogsRow.addView(viewLogsBtn)
        logsBox.addView(viewLogsRow)

        // Divider 1
        val logsDiv1 = android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, UITheme.dpToPx(this@SettingsActivity, 8), 0, UITheme.dpToPx(this@SettingsActivity, 8))
            }
            setBackgroundColor(Color.parseColor(UITheme.STROKE_COLOR))
        }
        logsBox.addView(logsDiv1)

        // Copy Logs Row
        val copyLogsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, UITheme.dpToPx(this@SettingsActivity, 4), 0, UITheme.dpToPx(this@SettingsActivity, 4))
        }

        val copyLogsInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val copyLogsTitle = TextView(this).apply {
            text = "Copy Diagnostic Logs"
            UITheme.applyCardTitleStyle(this)
            textSize = 15f
        }
        copyLogsInfo.addView(copyLogsTitle)

        val copyLogsSub = TextView(this).apply {
            text = "Copy debug output to clipboard"
            UITheme.applyMetadataStyle(this)
        }
        copyLogsInfo.addView(copyLogsSub)
        copyLogsRow.addView(copyLogsInfo)

        val copyLogsBtn = Button(this).apply {
            text = "Copy"
            textSize = 12f
            background = UITheme.createBadgeDrawable(this@SettingsActivity, "#2563EB", 10)
            setTextColor(Color.WHITE)
            setOnClickListener {
                val copied = TeleflixLogger.copyLogsToClipboard(this@SettingsActivity)
                if (copied) {
                    Toast.makeText(this@SettingsActivity, "📋 Diagnostic logs copied to clipboard!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@SettingsActivity, "Failed to copy logs to clipboard", Toast.LENGTH_SHORT).show()
                }
            }
        }
        copyLogsRow.addView(copyLogsBtn)
        logsBox.addView(copyLogsRow)

        // Divider 2
        val logsDiv2 = android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(0, UITheme.dpToPx(this@SettingsActivity, 8), 0, UITheme.dpToPx(this@SettingsActivity, 8))
            }
            setBackgroundColor(Color.parseColor(UITheme.STROKE_COLOR))
        }
        logsBox.addView(logsDiv2)

        // Clear Logs Row
        val clearLogsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, UITheme.dpToPx(this@SettingsActivity, 4), 0, UITheme.dpToPx(this@SettingsActivity, 4))
        }

        val clearLogsInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val clearLogsTitle = TextView(this).apply {
            text = "Clear Diagnostic Logs"
            UITheme.applyCardTitleStyle(this)
            textSize = 15f
        }
        clearLogsInfo.addView(clearLogsTitle)

        val clearLogsSub = TextView(this).apply {
            text = "Purge buffered log history"
            UITheme.applyMetadataStyle(this)
        }
        clearLogsInfo.addView(clearLogsSub)
        clearLogsRow.addView(clearLogsInfo)

        val clearLogsBtn = Button(this).apply {
            text = "Clear"
            textSize = 12f
            background = UITheme.createBadgeDrawable(this@SettingsActivity, "#475569", 10)
            setTextColor(Color.WHITE)
            setOnClickListener {
                TeleflixLogger.clearLogs()
                Toast.makeText(this@SettingsActivity, "Diagnostic logs cleared", Toast.LENGTH_SHORT).show()
            }
        }
        clearLogsRow.addView(clearLogsBtn)
        logsBox.addView(clearLogsRow)
        logsSectionContainer.addView(logsBox)

        setContentView(scrollView)

        CoroutineScope(Dispatchers.IO).launch {
            val sizeBytes = try { TelegramRepository.getCacheSize(this@SettingsActivity) } catch (_: Exception) { 0L }
            val sizeMb = sizeBytes / (1024.0 * 1024.0)
            withContext(Dispatchers.Main) {
                try { cacheSub.text = String.format("Cache: %.1f MB", sizeMb) } catch (_: Exception) {}
            }
        }
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
        if (!TdlibManager.isApiCredentialsConfigured(this)) {
            AlertDialog.Builder(this)
                .setTitle("API Credentials Required")
                .setMessage("Please enter your Telegram App API ID and API Hash from https://my.telegram.org in the API Credentials section above before connecting your account.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

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

    private fun showTelegramChatPicker() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(UITheme.BACKGROUND))
            val pad = UITheme.dpToPx(this@SettingsActivity, 16)
            setPadding(pad, pad, pad, pad)
        }

        val titleText = TextView(this).apply {
            text = "💬 Select Telegram Channels & Chats"
            UITheme.applySectionTitleStyle(this)
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        dialogView.addView(titleText)

        val subText = TextView(this).apply {
            text = "Select channels, groups, or archived chats to include in your catalog."
            UITheme.applyMetadataStyle(this)
            setPadding(0, 0, 0, UITheme.dpToPx(this@SettingsActivity, 12))
        }
        dialogView.addView(subText)

        val searchInput = EditText(this).apply {
            hint = "🔍 Search chats by name..."
            setHintTextColor(Color.parseColor(UITheme.TEXT_SECONDARY))
            setTextColor(Color.WHITE)
            background = UITheme.createInputBackground(this@SettingsActivity)
            val pV = UITheme.dpToPx(this@SettingsActivity, 10)
            val pH = UITheme.dpToPx(this@SettingsActivity, 12)
            setPadding(pH, pV, pH, pV)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, UITheme.dpToPx(this@SettingsActivity, 12))
            }
        }
        dialogView.addView(searchInput)

        val contentContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, UITheme.dpToPx(this@SettingsActivity, 350))
        }

        var activeChatsList: List<TelegramChatInfo> = TelegramRepository.getCachedJoinedChatsInfo(this@SettingsActivity)
        val hasCachedChats = activeChatsList.isNotEmpty()

        val loadingIndicator = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = if (hasCachedChats) android.view.View.GONE else android.view.View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER)
        }
        contentContainer.addView(loadingIndicator)

        val pickerRecyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            visibility = if (hasCachedChats) android.view.View.VISIBLE else android.view.View.GONE
        }
        val currentMonitored = TdlibManager.getChannels(this).map { it.username }.toMutableSet()
        val pickerAdapter = ChatPickerAdapter(activeChatsList, currentMonitored, this@SettingsActivity)
        pickerRecyclerView.adapter = pickerAdapter
        contentContainer.addView(pickerRecyclerView)
        dialogView.addView(contentContainer)

        var alertDialog: AlertDialog? = null
        alertDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Save Selected") { _, _ ->
                TdlibManager.setChannels(this@SettingsActivity, currentMonitored.toList())
                Toast.makeText(this@SettingsActivity, "Updated monitored channels!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()

        fun filterList(filterQuery: String = "") {
            val chats = activeChatsList
            val filtered = if (filterQuery.isBlank()) chats else chats.filter {
                it.title.contains(filterQuery, ignoreCase = true)
            }
            pickerAdapter.updateList(filtered)
        }

        alertDialog.show()

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterList(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Background update for fresh chats
        CoroutineScope(Dispatchers.IO).launch {
            val chats = TelegramRepository.getJoinedChatsInfo(forceRefresh = true)
            withContext(Dispatchers.Main) {
                if (alertDialog.isShowing) {
                    activeChatsList = chats
                    loadingIndicator.visibility = android.view.View.GONE
                    pickerRecyclerView.visibility = android.view.View.VISIBLE
                    filterList(searchInput.text?.toString() ?: "")
                }
            }
        }
    }

    private class ChatPickerAdapter(
        private var chatsList: List<TelegramChatInfo>,
        private val currentMonitored: MutableSet<String>,
        private val context: Context
    ) : RecyclerView.Adapter<ChatPickerAdapter.ViewHolder>() {

        class ViewHolder(
            val row: LinearLayout,
            val avatarView: ImageView,
            val titleV: TextView,
            val subV: TextView,
            val checkBox: CheckBox
        ) : RecyclerView.ViewHolder(row)

        fun updateList(newChats: List<TelegramChatInfo>) {
            chatsList = newChats
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = chatsList.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = UITheme.createRippleCardShape(context, UITheme.CARD, 12, UITheme.STROKE_COLOR)
                val p = UITheme.dpToPx(context, 10)
                setPadding(p, p, p, p)
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, UITheme.dpToPx(context, 6))
                }
            }

            val avatarView = ImageView(context).apply {
                val sz = UITheme.dpToPx(context, 40)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    setMargins(0, 0, UITheme.dpToPx(context, 10), 0)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            row.addView(avatarView)

            val infoLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleV = TextView(context).apply {
                UITheme.applyCardTitleStyle(this)
                textSize = 13f
            }
            infoLayout.addView(titleV)

            val subV = TextView(context).apply {
                UITheme.applyMetadataStyle(this)
                textSize = 11f
            }
            infoLayout.addView(subV)
            row.addView(infoLayout)

            val checkBox = CheckBox(context)
            row.addView(checkBox)

            return ViewHolder(row, avatarView, titleV, subV, checkBox)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val chat = chatsList[position]
            val chatKey = chat.chatId.toString()
            val chatKeyNo100 = chatKey.removePrefix("-100")
            val isChecked = currentMonitored.contains(chatKey) ||
                    currentMonitored.contains(chatKeyNo100) ||
                    currentMonitored.contains("-100$chatKey") ||
                    (chat.username != null && currentMonitored.contains("@" + chat.username))

            holder.titleV.text = chat.title
            holder.subV.text = when {
                chat.isBot -> "🤖 Bot"
                chat.isChannel -> "📢 Channel"
                chat.isGroup -> "👥 Group"
                chat.isArchived -> "📦 Archived"
                else -> "💬 Chat"
            }

            if (chat.photoFileId != null && chat.photoFileId > 0) {
                val thumbUrl = TelegramStreamingProxy.getThumbnailUrl(chat.photoFileId)
                com.bumptech.glide.Glide.with(context)
                    .load(thumbUrl)
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .error(android.R.drawable.ic_menu_gallery)
                    .circleCrop()
                    .into(holder.avatarView)
            } else {
                holder.avatarView.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            holder.checkBox.setOnCheckedChangeListener(null)
            holder.checkBox.isChecked = isChecked
            holder.checkBox.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    currentMonitored.add(chatKey)
                } else {
                    currentMonitored.remove(chatKey)
                    currentMonitored.remove(chatKeyNo100)
                    currentMonitored.remove("-100$chatKey")
                    if (chat.username != null) {
                        currentMonitored.remove("@" + chat.username)
                    }
                }
            }

            holder.row.setOnClickListener {
                holder.checkBox.isChecked = !holder.checkBox.isChecked
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try { TelegramService.start(this) } catch (_: Exception) {}
        }
    }

    private fun showLogsViewerDialog() {
        val logsText = TeleflixLogger.getFormattedLogs()
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(UITheme.BACKGROUND))
        }
        val textView = TextView(this).apply {
            text = logsText
            textSize = 12f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextColor(Color.parseColor("#38BDF8"))
            val pad = UITheme.dpToPx(this@SettingsActivity, 16)
            setPadding(pad, pad, pad, pad)
            setTextIsSelectable(true)
        }
        scrollView.addView(textView)

        AlertDialog.Builder(this)
            .setTitle("📋 Diagnostic & Streaming Logs")
            .setView(scrollView)
            .setPositiveButton("Copy All") { _, _ ->
                TeleflixLogger.copyLogsToClipboard(this)
                Toast.makeText(this, "Copied logs to clipboard!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
