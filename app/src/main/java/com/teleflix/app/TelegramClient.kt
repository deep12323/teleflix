package com.teleflix.app

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TelegramApiException(message: String) : Exception(message)

object TelegramClient {
    private const val TAG = "TelegramClient"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isAutoCleanerRunning = false

    private val _authState = MutableStateFlow<TelegramAuthState>(TelegramAuthState.Idle)
    val authState: StateFlow<TelegramAuthState> = _authState.asStateFlow()

    private var client: Client? = null
    private var isLibraryLoaded = false
    private var libraryLoadError: String? = null

    var isAvailable: Boolean = false
        private set

    private fun loadNativeLibrary(): Boolean {
        if (libraryLoadError != null) return false
        if (isLibraryLoaded) return true

        try {
            System.loadLibrary("tdjni")
            isLibraryLoaded = true
            isAvailable = true
            Log.d(TAG, "System.loadLibrary(tdjni) succeeded in standalone APK!")
            return true
        } catch (e: Throwable) {
            val err = "Failed to load libtdjni.so: ${e.message}"
            Log.e(TAG, err, e)
            libraryLoadError = err
            return false
        }
    }

    fun initialize(context: Context) {
        if (client != null) return
        _authState.value = TelegramAuthState.Initializing
        val isLoaded = loadNativeLibrary()
        if (!isLoaded) {
            _authState.value = TelegramAuthState.Error(libraryLoadError ?: "TDLib native library not available")
            return
        }

        scope.launch {
            if (client != null) return@launch
            if (!isAvailable) {
                _authState.value = TelegramAuthState.Error(libraryLoadError ?: "TDLib native library not available")
                return@launch
            }
            try {
                val dbDir = File(context.filesDir, "tdlib")
                val filesDir = File(context.cacheDir, "tdlib_files")
                if (!dbDir.exists()) dbDir.mkdirs()
                if (!filesDir.exists()) filesDir.mkdirs()
                try { File(context.filesDir, "tdlib_files").deleteRecursively() } catch (_: Exception) {}

                client = Client.create(
                    { update -> handleUpdate(context, update) },
                    { e -> Log.e(TAG, "Update exception", e) },
                    { e -> Log.e(TAG, "Default exception", e) }
                )
            } catch (e: Throwable) {
                Log.e(TAG, "TDLib Client.create failed", e)
                _authState.value = TelegramAuthState.Error("TDLib initialization failed: ${e.message}")
            }
        }
    }

    private fun sendTdlibParameters(context: Context) {
        val apiId = TdlibManager.getApiId(context)
        val apiHash = TdlibManager.getApiHash(context)

        if (apiId <= 0 || apiHash.isBlank()) {
            _authState.value = TelegramAuthState.Error(
                "Telegram API Credentials missing. Please enter your API ID and API Hash from https://my.telegram.org in Settings."
            )
            return
        }

        val dbDir = File(context.filesDir, "tdlib").absolutePath
        val filesDir = File(context.cacheDir, "tdlib_files").absolutePath
        client?.send(TdApi.SetTdlibParameters().also { p ->
            p.apiId = apiId
            p.apiHash = apiHash
            p.databaseDirectory = dbDir
            p.filesDirectory = filesDir
            p.databaseEncryptionKey = getOrGenerateDbKey(context)
            p.useFileDatabase = true
            p.useChatInfoDatabase = true
            p.useMessageDatabase = true
            p.useSecretChats = false
            p.systemLanguageCode = "en"
            p.deviceModel = Build.MODEL ?: "Android Device"
            p.systemVersion = "Android ${Build.VERSION.RELEASE}"
            p.applicationVersion = "1.0.0"
        }, { result ->
            if (result is TdApi.Error) {
                Log.e(TAG, "SetTdlibParameters failed: ${result.code} ${result.message}")
                if (result.message.contains("key", ignoreCase = true) || result.code == 401) {
                    context.getSharedPreferences("teleflix_tdlib_prefs", Context.MODE_PRIVATE).edit().remove("db_key").apply()
                    try { java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("tdlib_db_key_alias") } } catch (_: Throwable) {}
                    try { File(dbDir).deleteRecursively() } catch (_: Throwable) {}
                    try { File(filesDir).deleteRecursively() } catch (_: Throwable) {}
                    reset()
                    initialize(context)
                } else {
                    _authState.value = TelegramAuthState.Error("TDLib init failed: ${result.message}")
                }
                return@send
            }
            updateCacheLimit()
        })
    }

    fun updateCacheLimit() {
        client?.send(TdApi.SetOption("storage_max_size", TdApi.OptionValueInteger(2000000000000L)), null)
        client?.send(TdApi.SetOption("storage_max_files", TdApi.OptionValueInteger(2000000L)), null)
        client?.send(TdApi.SetOption("download_files_in_background", TdApi.OptionValueBoolean(true)), null)
        client?.send(TdApi.SetOption("online", TdApi.OptionValueBoolean(true)), null)
        client?.send(TdApi.SetOption("max_download_file_size", TdApi.OptionValueInteger(50000000000L)), null)
        client?.send(TdApi.SetOption("prefer_ipv6", TdApi.OptionValueBoolean(false)), null)
        client?.send(TdApi.SetNetworkType(TdApi.NetworkTypeOther()), null)
    }

    fun deleteFile(fileId: Int) {
        if (fileId <= 0) return
        client?.send(TdApi.CancelDownloadFile(fileId, false), null)
    }

    fun optimizeStorage() {
        client?.send(TdApi.OptimizeStorage().also { req ->
            req.size = 0
            req.ttl = 0
            req.count = 0
            req.immunityDelay = 0
            req.fileTypes = arrayOf(
                TdApi.FileTypeDocument(),
                TdApi.FileTypeVideo(),
                TdApi.FileTypeVideoNote(),
                TdApi.FileTypeAudio(),
                TdApi.FileTypeVoiceNote(),
                TdApi.FileTypeAnimation(),
                TdApi.FileTypeUnknown()
            )
            req.chatIds = longArrayOf()
            req.excludeChatIds = longArrayOf()
            req.returnDeletedFileStatistics = false
            req.chatLimit = 0
        }, null)
    }

    fun clearMediaCacheSync(context: Context) {
        if (DownloadManager.hasActiveDownloads()) return
        runCatching { optimizeStorage() }
        runCatching {
            val tdlibDbDir = File(context.filesDir, "tdlib")
            if (tdlibDbDir.exists()) {
                tdlibDbDir.listFiles()?.forEach { sub ->
                    val subName = sub.name.lowercase()
                    if (sub.isDirectory && subName != "database" && !subName.contains("db")) {
                        sub.deleteRecursively()
                    }
                }
            }
            val filesTdlib = File(context.filesDir, "tdlib_files")
            if (filesTdlib.exists()) filesTdlib.deleteRecursively()
            val cacheTdlib = File(context.cacheDir, "tdlib_files")
            if (cacheTdlib.exists()) cacheTdlib.deleteRecursively()
        }
    }

    fun clearMediaCache(context: Context) {
        scope.launch {
            clearMediaCacheSync(context)
        }
    }

    private fun handleUpdate(context: Context, obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateFile -> DownloadManager.onFileUpdate(context, obj.file)
            is TdApi.UpdateAuthorizationState -> handleAuthState(context, obj.authorizationState)
            is TdApi.UpdateConnectionState -> {
                val stateName = obj.state::class.simpleName ?: "Unknown"
                TeleflixLogger.log("TelegramDC", "[ConnectionState] -> $stateName")
            }
            is TdApi.Error -> {
                TeleflixLogger.log("TelegramDC", "[TDLib Error] code=${obj.code} message=${obj.message}", isError = true)
                val state = _authState.value
                if (state is TelegramAuthState.Initializing ||
                    state is TelegramAuthState.WaitPhone ||
                    state is TelegramAuthState.WaitQr ||
                    state is TelegramAuthState.WaitCode ||
                    state is TelegramAuthState.WaitPassword) {
                    _authState.value = TelegramAuthState.Error(obj.message)
                }
            }
        }
    }

    private fun handleAuthState(context: Context, state: TdApi.AuthorizationState) {
        Log.d(TAG, "authState -> ${state::class.simpleName}")
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> sendTdlibParameters(context)
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                _authState.value = TelegramAuthState.WaitPhone
            }
            is TdApi.AuthorizationStateWaitCode -> {
                val len = when (val t = state.codeInfo.type) {
                    is TdApi.AuthenticationCodeTypeTelegramMessage -> t.length
                    is TdApi.AuthenticationCodeTypeSms -> t.length
                    else -> 5
                }
                _authState.value = TelegramAuthState.WaitCode(len)
            }
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                _authState.value = TelegramAuthState.WaitQr(state.link)
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                _authState.value = TelegramAuthState.WaitPassword
            }
            is TdApi.AuthorizationStateReady -> {
                scope.launch {
                    val user = sendRequest(TdApi.GetMe()) as? TdApi.User
                    for (chatList in listOf(TdApi.ChatListMain(), TdApi.ChatListArchive())) {
                        try { sendRequest(TdApi.LoadChats(chatList, 100)) } catch (_: Exception) {}
                    }
                    val phone = user?.phoneNumber?.let { if (!it.startsWith("+")) "+$it" else it } ?: ""
                    TdlibManager.setSessionActive(context, true, phone)
                    try { TelegramRepository.sessionMarker(context).createNewFile() } catch (_: Exception) {}
                    try { TelegramService.start(context) } catch (_: Exception) {}
                    _authState.value = TelegramAuthState.Ready(
                        firstName = user?.firstName ?: "Telegram User",
                        userId = user?.id ?: 0L
                    )
                }
            }
            is TdApi.AuthorizationStateClosing,
            is TdApi.AuthorizationStateClosed -> {
                TdlibManager.setSessionActive(context, false, "")
                try { TelegramRepository.sessionMarker(context).delete() } catch (_: Exception) {}
                try { TelegramService.stop(context) } catch (_: Exception) {}
                _authState.value = TelegramAuthState.Idle
            }
            else -> {}
        }
    }

    fun clearNativeLibraryCache(context: Context) {
        // Native libraries are directly packaged in APK jniLibs, no manual extraction cache cleanup needed
    }

    fun requestQrCode() {
        client?.send(TdApi.RequestQrCodeAuthentication(LongArray(0)), null)
    }

    fun submitPhone(phone: String) {
        client?.send(TdApi.SetAuthenticationPhoneNumber(phone, TdApi.PhoneNumberAuthenticationSettings(false, false, false, false, false, null, emptyArray())), null)
    }

    fun submitCode(code: String) {
        client?.send(TdApi.CheckAuthenticationCode(code), null)
    }

    fun submitPassword(password: String) {
        client?.send(TdApi.CheckAuthenticationPassword(password), null)
    }

    suspend fun sendRequest(
        function: TdApi.Function<out TdApi.Object>,
        timeoutMs: Long = 15_000L
    ): TdApi.Object? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val c = client
            if (c == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            c.send(function) { result ->
                if (cont.isActive) {
                    if (result is TdApi.Error) cont.resumeWithException(TelegramApiException(result.message))
                    else cont.resume(result)
                }
            }
        }
    }

    fun logout(context: Context) {
        client?.send(TdApi.LogOut(), { _ ->
            TdlibManager.setSessionActive(context, false, "")
            try { TelegramService.stop(context) } catch (_: Exception) {}
            _authState.value = TelegramAuthState.Idle
        })
    }

    fun reset() {
        client?.send(TdApi.Close(), null)
        client = null
        _authState.value = TelegramAuthState.Idle
    }

    private fun getOrGenerateDbKey(context: Context): ByteArray {
        val prefs = context.getSharedPreferences("teleflix_tdlib_prefs", Context.MODE_PRIVATE)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            val encodedKey = prefs.getString("db_key_legacy", null)
            if (encodedKey != null) {
                return Base64.decode(encodedKey, Base64.DEFAULT)
            }
            val rawDbKey = ByteArray(32)
            java.security.SecureRandom().nextBytes(rawDbKey)
            prefs.edit().putString("db_key_legacy", Base64.encodeToString(rawDbKey, Base64.DEFAULT)).apply()
            return rawDbKey
        }

        val encryptedKeyBase64 = prefs.getString("db_key", null)
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val alias = "tdlib_db_key_alias"

        if (encryptedKeyBase64 == null) {
            val keyGenerator = javax.crypto.KeyGenerator.getInstance(
                android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )
            val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                alias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
            keyGenerator.init(spec)
            val secretKey = keyGenerator.generateKey()

            val rawDbKey = ByteArray(32)
            java.security.SecureRandom().nextBytes(rawDbKey)

            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedDbKey = cipher.doFinal(rawDbKey)

            val combined = iv + encryptedDbKey
            prefs.edit().putString("db_key", Base64.encodeToString(combined, Base64.DEFAULT)).apply()
            return rawDbKey
        } else {
            try {
                val combined = Base64.decode(encryptedKeyBase64, Base64.DEFAULT)
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                val secretKey = keyStore.getKey(alias, null) as? javax.crypto.SecretKey
                    ?: throw IllegalStateException("Keystore key is null")

                val iv = combined.copyOfRange(0, 12)
                val encryptedDbKey = combined.copyOfRange(12, combined.size)

                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, javax.crypto.spec.GCMParameterSpec(128, iv))
                return cipher.doFinal(encryptedDbKey)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt DB key, resetting TDLib", e)
                prefs.edit().remove("db_key").apply()
                try { keyStore.deleteEntry(alias) } catch (_: Exception) {}
                try { File(context.filesDir, "tdlib").deleteRecursively() } catch (_: Exception) {}
                try { File(context.cacheDir, "tdlib_files").deleteRecursively() } catch (_: Exception) {}
                try { File(context.filesDir, "tdlib_files").deleteRecursively() } catch (_: Exception) {}
                return getOrGenerateDbKey(context)
            }
        }
    }
}
