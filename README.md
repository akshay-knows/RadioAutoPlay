# 📻 Radio AutoPlay

An Android app that **automatically starts playing a radio/audio stream when your charger is plugged in** and stops when it's unplugged — no interaction required.

---

## ✨ Features

| Feature | Details |
|---|---|
| **Auto-play on charge** | Detects `ACTION_POWER_CONNECTED` and starts the bathroom audio sequence |
| **Auto-stop on unplug** | Detects `ACTION_POWER_DISCONNECTED` and stops cleanly |
| **Lock-screen charger monitor** | Runs a small foreground monitor so charger events keep working while the phone is locked |
| **Animated splash screen** | Shows a quick animated launch screen before the main controls |
| **Shuffle mode** | Picks a random saved stream every time you plug in |
| **Sequential mode** | Cycles through your streams in order |
| **Playback source switch** | Choose whether charger autoplay prefers direct in-app streams or webpage station players |
| **Stable APK updates** | Debug and release builds use the same bundled project signing key from v1.2 onward |
| **In-app updater** | Checks GitHub Releases, prompts inside the app, downloads the APK, and opens Android's installer |
| **Multiple intro sounds** | Bundled startup sounds and optional custom audio are chosen randomly while the station starts buffering |
| **Voice announcements** | Announces time every hour/half-hour, stream failures, network loss, low battery, and music start without reading link names |
| **Quiet hours** | Automatically refuses or stops playback from 12:00 AM to 6:00 AM |
| **Stream failover watchdog** | If a stream errors or does not start within 17 seconds, the app automatically tries another saved stream |
| **Self-healing stations** | Failed stations are skipped for 30 minutes, then automatically retried later |
| **HTML player link support** | If a station returns a simple browser player page, the app extracts `<audio>`, `<video>`, or `<source>` media URLs and retries with audio-friendly headers |
| **Manage stream URLs** | Add, play, or remove any number of stream URLs |
| **CSV import** | Import many stream links from a CSV file exported from Excel or Google Sheets |
| **Preloaded streams** | Starts with 23 built-in radio/news/music stream URLs from the provided workbook |
| **Web station pool** | Includes additional online radio station webpages that can be played through the WebView fallback |
| **No hardcoded links** | All URLs are stored in SharedPreferences; fully user-configurable |
| **Foreground service** | Keeps playing with screen off; shows a persistent notification |
| **Light & lean** | No third-party streaming SDK; uses Android's built-in `MediaPlayer` |
| **Connecting loop** | Plays the bundled `get_connected` audio in a loop after the intro until the stream is ready |

---

## 📱 Compatibility

- **Minimum SDK:** API 19 (Android 4.4 KitKat)
- **Target SDK:** API 33 (Android 13)
- Works on Android versions from 4.4 to modern Android releases

---

## 🏗 Architecture

```
app/
├── ChargerReceiver.java    # Detects plug/unplug via BroadcastReceiver
├── RadioService.java       # Foreground service — MediaPlayer lifecycle
├── StreamUrlManager.java   # SharedPreferences CRUD for stream URLs
├── UrlAdapter.java         # RecyclerView adapter for the URL list
└── MainActivity.java       # UI — add/remove URLs, manual play/stop, shuffle toggle
```

---

## 🚀 Getting Started

### Build

1. Clone this repo  
2. Open in **Android Studio** (Arctic Fox or newer)  
3. Click **Run ▶**

Or build from command line:
```bash
./gradlew assembleDebug
```
The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

Built APKs are also archived by version under:

```text
apk-releases/v1.4/RadioAutoPlay-v1.4.apk
```

### Updating an installed APK

Android only allows an app update when the package name, signing certificate, and version code are valid:

- Package name stays `com.radioautoplay`
- Version code is now `5`
- From v1.2 onward, debug and release APKs are signed with the bundled `app/radioautoplay-upload.jks`

If your currently installed APK was signed by Android Studio's old debug key or a GitHub runner key, Android may show an update/install conflict once. In that case, uninstall the old app one time, install v1.2, and future APKs built from this repo should update normally.

The in-app updater checks the latest GitHub Release and downloads the attached `.apk` without opening GitHub in the browser. Android still shows its normal install confirmation screen because regular apps cannot silently replace themselves.

### Publishing an update

1. Increase `versionCode` and `versionName` in `app/build.gradle`
2. Build and test locally
3. Commit and push the code
4. Push a matching version tag, for example `v1.3`

The `Release APK` GitHub workflow builds the signed APK, saves it under `apk-releases/<version>/`, and attaches it to the GitHub Release. Installed apps then see that release through the in-app updater.

### Add your first stream

1. Open the app  
2. Use the preloaded streams, or paste your own stream URL into the input field
3. Tap **+ Add Stream** if adding a custom stream
4. Choose **Webpage stations by default** if you want charger autoplay to prefer website radio players, or leave it off to prefer direct in-app streams
5. Plug in your charger — the app starts buffering immediately, plays an intro, loops the connecting audio if needed, then starts radio automatically 🎶

### Keep autoplay reliable

Open the app once after installing it. This starts the charger monitor notification, which keeps charger connect/disconnect detection alive while the phone is locked.

After a reboot, the app restarts the charger monitor automatically using Android's boot broadcasts. Stream URLs are stored in device-protected storage on Android 7+, so the monitor can be ready even before the first unlock after boot.

On some Android skins, also allow this app in battery/autostart settings:

- Disable battery optimization for **Radio AutoPlay**
- Allow **Auto start** / **Run in background**
- Allow notifications, so Android can keep the foreground monitor alive

Android 10+ usually blocks apps from visually opening their screen from the background or lock screen. The app is designed to play/stop audio in the background instead; tap the persistent notification if you want to open the screen.

Webpage stations are opened inside a hidden WebView in the foreground playback service. The service requests audio focus, keeps CPU/Wi-Fi locks, injects autoplay/click handling, and switches away if the page does not start within 17 seconds.

### Import streams from Excel / CSV

Custom intros are optional. If no custom intro sound is saved, the app randomly picks one of the bundled startup sounds and then uses the connecting loop while the station buffers.

Create a spreadsheet with one stream URL per row, then export or save it as a `.csv` file.

Recommended format:

```csv
url
https://example.com/stream1.mp3
https://example.com/stream2
https://example.com/live.m3u8
```

The importer scans every cell in the CSV and imports values that start with `http://` or `https://`, so this also works:

```csv
name,url
Morning Radio,https://example.com/morning
Night Radio,https://example.com/night
```

Duplicate links are skipped automatically.

---

## 🔧 Supported Stream Formats

Any URL that Android's `MediaPlayer` can handle:
- Icecast / SHOUTcast HTTP streams (`.mp3`, `.aac`, `.ogg`)
- Raw MP3/AAC HTTP streams
- HLS (`.m3u8`) — Android 4.1+
- Most proxy streams
- Simple HTML player pages that contain an `<audio>`, `<video>`, or `<source>` stream URL

---

## 📋 Permissions Used

| Permission | Why |
|---|---|
| `INTERNET` | To stream audio |
| `FOREGROUND_SERVICE` | To keep playing with screen off |
| `RECEIVE_BOOT_COMPLETED` | (Future) Re-register receiver after reboot |
| `WAKE_LOCK` | Prevent CPU sleep during playback |

---

## 🛠 How It Works

```
Charger plugged in
      │
      ▼
ChargerReceiver.onReceive(ACTION_POWER_CONNECTED)
      │
      ├── shuffle ON?  → StreamUrlManager.getNextUrl() picks random URL
      └── shuffle OFF? → StreamUrlManager.getNextUrl() picks next in list
      │
      ▼
RadioService starts as a ForegroundService
Bundled or custom random intro plays while the stream begins buffering
      │
      ▼
Connecting audio loops until MediaPlayer.prepareAsync() finishes
      │
      ├── starts within 17 seconds → MediaPlayer.start()
      └── fails or times out      → try another saved stream
      │
      ▼
Charger unplugged → ChargerReceiver sends STOP → RadioService releases MediaPlayer
```

---

## License

MIT
