# Teleflix

Teleflix is an Android app that lets you stream movies, TV shows, and videos directly from Telegram channels and Cinemeta catalogs without downloading entire files first.

It runs a local HTTP streaming proxy powered by TDLib. When you play a video, the app fetches byte ranges on demand directly from Telegram's servers and pipes them to your player (built-in ExoPlayer, VLC, MPV, MX Player, etc.).

---

## Features

- **Dual Catalogs**: Switch between Cinemeta (movies & series metadata) and your monitored Telegram channels.
- **On-Demand Range Streaming**: Start watching instantly. Skip forward or back without waiting for full downloads.
- **Split & Archive Streaming**: Merges multi-part files (`.zip.001`, `.part1.rar`) on the fly while streaming.
- **Custom Player Choice**: Select ExoPlayer, MPV, VLC, MX Player, or your system launcher. Keeps track of watch history and resume points.
- **Clean Dark Theme**: Dark AMOLED UI (`#0B0B0F`) with rounded cards and clear typography.

---

## How to Connect Your Telegram Account

To stream media from your channels or private chats, you need to sign in with your Telegram account inside the app.

### Step 1: Open Settings
Open Teleflix on your phone and tap the **⚙️ Settings** icon in the top right corner.

### Step 2: Set API Credentials (Required for Telegram Login)
To connect Telegram, you need to provide your own API credentials from [my.telegram.org](https://my.telegram.org):
1. Go to [my.telegram.org](https://my.telegram.org) in your web browser and log in with your phone number.
2. Click **API development tools**.
3. Create an application (App title and short name can be anything).
4. Copy your **App api_id** and **App api_hash**.
5. In Teleflix **Settings → TDLib API Credentials**, enter your `API ID` and `API Hash`, then tap **Save Credentials & Reload TDLib**.

### Step 3: Log In with Phone Number
1. In Teleflix **Settings → Teleflix Login & Account**, tap **Connect Telegram Account**.
2. Enter your phone number with country code (e.g., `+14155552671` or `+919876543210`).
3. Telegram will send a verification code (OTP) via your official Telegram app or SMS.
4. Enter the verification code in the popup prompt.
5. If your account has Two-Step Verification (2FA password) enabled, enter your 2FA password when prompted.
6. Once authenticated, your session status will show **Connected**.

### Step 4: Add Channels to Monitored Catalog
1. Go to **Settings → Catalogue Monitored Channels**.
2. Type the `@channel_username` (e.g., `@movie_channel`) and tap **Add**.
3. Toggle the main screen mode to **💬 Telegram Channels** to view and stream videos from your added channels.

---

## Building from Source

### Requirements
- Android SDK 34+
- Java 17 / Kotlin 1.9+
- Gradle 8.x

### Build Command
```bash
./gradlew :app:assembleDebug
```
The compiled APK will be created at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## License & Disclaimer

This project is licensed under the [MIT License](file:///root/teleflix-android-app/LICENSE). See the [LICENSE](file:///root/teleflix-android-app/LICENSE) file for details.

*Disclaimer: Teleflix is for personal media access. Ensure you have the rights to stream content from channels you access.*

