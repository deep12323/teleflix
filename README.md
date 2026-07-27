# 📱 Teleflix - Standalone Android Application

A high-performance standalone Android application designed for seamless media streaming directly from Telegram cloud servers using an embedded TDLib byte-range streaming proxy engine and interactive catalogs.

---

## 🚀 Key Features

- **Dual-Mode Catalogs**: Seamlessly toggle between **🎬 Cinemeta** (Top Movies, Trending TV Series, New Releases) and **💬 Monitored Telegram Channels** with full-width custom list views.
- **Real-Time Split File & ZIP Archive Streaming**: Built-in HTTP streaming proxy sequentially merges multi-part archives (`.zip.001`, `.part1.rar`, `.z01`) and reads directly inside single `.zip` video archives over byte ranges on the fly—zero waiting, extracting, or wasting internal phone disk space!
- **Intelligent Caption-to-Title Parsing**: Automatically reads message captions in educational study batches or media posts to generate clean, self-explanatory video titles when uploaders omit filenames.
- **Widescreen 16:9 Posters & Thumbnails**: Pulls live real-time video preview thumbnails directly via TDLib, replacing generic icons with vibrant visual cards and actual human-readable Telegram chat names.
- **Interactive Back Navigation**: Dedicated on-screen header banners (**"⬅ Back to Channels • Browsing: [Channel]"**) alongside hardware and swipe gesture overrides for intuitive catalog navigation.
- **Universal Player Compatibility**: Stream directly inside our responsive built-in **ExoPlayer** with playback speed & aspect ratio controls, or transfer playback instantly into **VLC**, **MX Player**, or **MPV** with automatic playback progress resume memory.
- **Secure Telegram Authentication**: Direct TDLib phone number and OTP login with secure local session preservation.

---

## 🛠️ Building & Installing the APK

### Automatic Cloud Releases (GitHub Actions)
Each push to the master branch automatically builds a compiled APK in GitHub Releases:
1. Navigate to **[Releases](https://github.com/deepu2135/teleflix-android-app/releases)** in this repository.
2. Download the latest `app-debug.apk` directly to your Android device and install!

### Local Build (Linux / macOS / Windows)
To compile the APK manually using Gradle:
```bash
chmod +x gradlew
./gradlew :app:assembleDebug
```
Once completed, the generated APK will be output to:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 💡 Architecture Overview

**Teleflix** integrates an embedded local HTTP streaming engine running on `http://127.0.0.1`. When you tap any video or multi-part archive:
1. The app generates an authenticated internal stream URL.
2. When external or built-in video players make HTTP Range byte requests (such as skipping to minute 40 of a movie or lecture), the TDLib streaming engine (`TdApi.ReadFilePart`) directly queries Telegram cloud infrastructure in memory.
3. Split part boundaries are bridged automatically without stutter, treating complex archives as a unified solid media stream!
