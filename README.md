# BeatIt — Android Music Player

A standalone Android app that lets you search, download, and play music from YouTube — all stored locally on your device. Works offline after downloading.

## Features
- 🎵 **iPod Classic UI** — pixel-accurate retro design with 8 color themes
- 🔍 **YouTube search** — find any song instantly via NewPipe Extractor
- ⬇️ **Download locally** — songs saved to your device, playable offline
- 🎧 **MP3 & Opus** — choose your preferred audio format
- 📱 **Lightweight** — ~5MB APK, no bloat

## Architecture
```
APK
├── WebView (loads localhost:8080)
│   └── assets/ — index.html, app.js, style.css (iPod UI)
└── Kotlin Backend
    ├── NanoHTTPD — local HTTP server
    ├── NewPipe Extractor — YouTube search + stream extraction
    └── OkHttp — audio download with progress
```

## Build
```bash
# Requires: JDK 17, Android SDK
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Install
Transfer `app-debug.apk` to your Android device and install. You may need to enable "Install from Unknown Sources" in Settings.

## License
MIT
