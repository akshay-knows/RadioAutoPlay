# 📻 Radio AutoPlay

An Android app that **automatically starts playing a radio/audio stream when your charger is plugged in** and stops when it's unplugged — no interaction required.

---

## ✨ Features

| Feature | Details |
|---|---|
| **Auto-play on charge** | Detects `ACTION_POWER_CONNECTED` and starts the bathroom audio sequence |
| **Auto-stop on unplug** | Detects `ACTION_POWER_DISCONNECTED` and stops cleanly |
| **Shuffle mode** | Picks a random saved stream every time you plug in |
| **Sequential mode** | Cycles through your streams in order |
| **Bathroom intro theme** | Waits 5 seconds, plays the bundled intro MP3, then starts the radio |
| **Stream failover watchdog** | If a stream errors or does not start within 1 minute, the app automatically tries another saved stream |
| **Manage stream URLs** | Add, play, or remove any number of stream URLs |
| **CSV import** | Import many stream links from a CSV file exported from Excel or Google Sheets |
| **No hardcoded links** | All URLs are stored in SharedPreferences; fully user-configurable |
| **Foreground service** | Keeps playing with screen off; shows a persistent notification |
| **Light & lean** | No third-party streaming SDK; uses Android's built-in `MediaPlayer` |

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

### Add your first stream

1. Open the app  
2. Paste your stream URL (e.g. `https://s6.yesstreaming.net/proxy/john1237?mp=/live`) into the input field  
3. Tap **+ Add Stream**  
4. Plug in your charger — the app waits 5 seconds, plays the intro theme, then starts radio automatically 🎶

### Import streams from Excel / CSV

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
5-second quiet delay
      │
      ▼
Bundled intro MP3 plays
      │
      ▼
MediaPlayer.prepareAsync()
      │
      ├── starts within 1 minute → MediaPlayer.start()
      └── fails or times out      → try another saved stream
      │
      ▼
Charger unplugged → ChargerReceiver sends STOP → RadioService releases MediaPlayer
```

---

## License

MIT
