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

        // Session Status Box
        val sessionBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#161B28"))
            setPadding(24, 24, 24, 24)
        }

        val sessionTitle = TextView(this).apply {
            text = "Telegram Account Session (Native TDLib)"
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

        root.addView(sessionBox)

        // Channels Section
        val channelTitle = TextView(this).apply {
            text = "Monitored Telegram Channels"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 24, 0, 12)
        }
        root.addView(channelTitle)

        val addLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }

        val channelInput = EditText(this).apply {
            hint = "Enter @channel_name..."
            setHintTextColor(Color.parseColor("#6B7280"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#161B28"))
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
        root.addView(addLayout)

        channelContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(channelContainer)

        // API Credentials Box
        val apiBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#161B28"))
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 24, 0, 0)
            }
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

        root.addView(apiBox)
        setContentView(scrollView)

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
