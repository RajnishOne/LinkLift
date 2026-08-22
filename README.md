# LinkLift — Android YouTube, Instagram & Media Downloader

<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" height="96" alt="LinkLift Logo" />
</p>

<p align="center">
  <strong>Fast, private, all-in-one YouTube downloader, Instagram Reels downloader & media extractor for Android.</strong>
</p>

<p align="center">
  <a href="https://developer.android.com/about/versions/nougat"><img src="https://img.shields.io/badge/Android-7.0%2B%20(API%2024%2B)-3DDC84?style=flat&logo=android&logoColor=white" alt="Android API 24+"></a>
  <a href="https://kotlinlang.org/"><img src="https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?style=flat&logo=kotlin&logoColor=white" alt="Kotlin"></a>
  <a href="https://developer.android.com/jetpack/compose"><img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=flat&logo=jetpackcompose&logoColor=white" alt="Compose"></a>
  <a href="https://chaquo.com/chaquopy/"><img src="https://img.shields.io/badge/Python-3.13%20(Chaquopy)-3776AB?style=flat&logo=python&logoColor=white" alt="Python 3.13"></a>
  <a href="https://github.com/yt-dlp/yt-dlp"><img src="https://img.shields.io/badge/Powered%20by-yt--dlp%20%26%20Instaloader-FF0000?style=flat" alt="yt-dlp"></a>
  <a href="https://github.com/RajnishOne/LinkLift/releases"><img src="https://img.shields.io/badge/F--Droid%20%26%20FOSS-Ready-009688?style=flat&logo=f-droid&logoColor=white" alt="F-Droid Ready"></a>
  <a href="https://github.com/RajnishOne/LinkLift/pulls"><img src="https://img.shields.io/badge/PRs-welcome-brightgreen.svg?style=flat" alt="PRs Welcome"></a>
</p>

---

## 🌟 Overview

**LinkLift** is a native, modern Android video and audio downloader built with **Jetpack Compose** and **Material 3**. It functions as an all-in-one **YouTube downloader**, **Instagram downloader**, and multi-platform media extractor, letting you analyze, stream-preview, and download high-resolution videos, audio tracks, and images directly to your device.

Powered by an embedded **Python 3.13** runtime via Chaquopy, LinkLift leverages the latest `yt-dlp` and `instaloader` extraction engines combined with an on-device native `MediaMuxer` pipeline for combining separate 1080p/4K video and high-bitrate audio streams into standard MP4/WebM files with zero quality loss.

---

## 🎯 Supported Platforms & Features

| Platform | Supported Media Types | Key Features |
| :--- | :--- | :--- |
| **YouTube** | Videos (up to 4K/60fps), Shorts, Playlists, Channels, Audio-only (M4A/MP3/Opus) | Native dual-stream muxing, playlist batch expansion |
| **Instagram** | Reels, Posts, Stories, Carousels, Profile photos | Multi-slide carousel viewer, direct HD MP4 extraction |
| **TikTok** | HD Videos, Slideshows, Audio tracks | Clean downloads without watermark |
| **X / Twitter** | Videos, GIFs, High-res images | Multi-bitrate quality selector |
| **Reddit** | Videos with audio (v.redd.it), Galleries | Automatic DASH/HLS audio-video merging |
| **Pinterest** | HD Videos, Pins, Images | Full resolution image & video downloads |
| **SoundCloud** | Tracks, Sets, Albums | High-bitrate audio extraction with metadata |
| **1,000+ Sites** | Any website supported by `yt-dlp` (Vimeo, Dailymotion, Facebook, Twitch, etc.) | Direct stream extraction, custom format selector |

---

## ✨ Core Highlights

- **Fast YouTube & Instagram Downloader**: Download individual videos, shorts, reels, or complete playlists with a single tap.
- **On-Device Stream Merging (`MediaMuxer`)**: Automatically merges separate 1080p/1440p/4K video streams with high-quality AAC/M4A audio in a lightweight background foreground service.
- **In-App Fullscreen Media Player**: Built-in media player with **AndroidX Media3 (ExoPlayer)** featuring gesture controls (volume/brightness), playback speed toggles, and quality switching.
- **System Share Sheet Integration**: Send any link directly from YouTube, Instagram, browser, or social apps to LinkLift for instant parsing.
- **100% Tracker-Free & Privacy-First**: Zero Firebase, Zero Amplitude, Zero Google Play Services dependencies. No tracking, no ads, completely open-source (FOSS).
- **Clean Material 3 Design**: Dynamic Monet theming, dark mode, responsive layout, and smooth animations.

---

## 🏗️ Architecture & Tech Stack

```
LinkLift
 ├── app/src/main/java/com/rjnsdev/linklift/app/
 │    ├── ui/               # Jetpack Compose UI (HomeScreen, PreviewScreen, DownloadsScreen, BatchScreen)
 │    ├── util/             # URL parsers, Formatters, RemoteConfigHelper
 │    ├── LinkLiftViewModel # StateFlow-driven architecture and download orchestration
 │    └── MergeDownloadService # Foreground service for downloading & muxing dual-stream media
 └── app/src/main/python/
      ├── generic_media_resolver.py  # yt-dlp wrapper and format ladder pairing
      ├── instagram_resolver.py      # Instaloader wrapper for Instagram carousels/reels
      └── test_resolvers.py          # Python unit tests
```

- **UI Framework**: [Jetpack Compose](https://developer.android.com/jetpack/compose) with Material 3 components.
- **Embedded Python Engine**: [Chaquopy 17](https://chaquo.com/chaquopy/) running Python 3.10 with `yt-dlp` and `instaloader`.
- **Media Playback**: [AndroidX Media3 (ExoPlayer)](https://developer.android.com/guide/topics/media/media3).
- **Networking**: [OkHttp 4](https://square.github.io/okhttp/) with custom header sanitization and resilient retries.
- **Image Loading**: [Coil](https://coil-kt.github.io/coil/).
- **Data Persistence**: AndroidX DataStore Preferences.

---

## 🚀 Getting Started & Building

### Prerequisites

1. **Java Development Kit (JDK)**: JDK 17 or JDK 21 (e.g., Eclipse Temurin or OpenJDK).
2. **Android SDK**: Android SDK with API Level 34+ installed.
3. **Python 3.10**: Required on your development machine for Chaquopy build-time packaging.
   - **macOS**: `brew install python@3.10`
   - **Ubuntu/Debian**: `sudo apt update && sudo apt install python3.10 python3.10-venv`
   - **Windows**: Install Python 3.10 from [python.org](https://www.python.org/downloads/) and ensure it is added to your `PATH`.

---

### Step 1: Clone the Repository

```bash
git clone https://github.com/RajnishOne/LinkLift.git
cd LinkLift
```

---

### Step 2: Configure Environment (`local.properties`)

Copy `local.properties.example` to `local.properties` and set your Android SDK directory:

```bash
cp local.properties.example local.properties
```

Edit `local.properties`:
```properties
sdk.dir=/path/to/your/android-sdk

# (Optional) Explicit path to Python 3.10 if not auto-detected:
# python.path=/opt/homebrew/bin/python3.10
```

---

### Step 3: Build & Run

#### Run Unit Tests:
```bash
./gradlew testDebugUnitTest
```

#### Run Python Resolver Tests:
```bash
python3 app/src/main/python/test_resolvers.py
```

#### Build Debug APK:
```bash
./gradlew assembleDebug
```
The output APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

#### Install and Run on a Connected Device:
```bash
./gradlew installDebug
# or run the helper script:
./scripts/run-android.sh
```

---

## ⚙️ Remote Configuration

Platform availability toggles (such as YouTube, SoundCloud, and Imgur download availability) are managed via the open [remote_config.json](remote_config.json) file hosted in this repository:

```json
{
  "is_youtube_available": true,
  "is_soundcloud_available": true,
  "is_imgur_available": true
}
```

The app queries `raw.githubusercontent.com` and jsDelivr CDN on startup with resilient offline fallbacks.

---

## 🔒 Privacy & Analytics

- **No Google Play Services requirement**: The app functions fully on de-Googled devices and custom ROMs.
- **Zero Trackers / Analytics**: Completely telemetry-free with no analytics SDKs.
- **Local Storage**: All downloads, download histories, and preferences remain strictly on the user's device.

---

## ⚖️ Legal Disclaimer

LinkLift is developed for **educational and personal archival purposes only**. 

- Please respect the copyright and intellectual property rights of content creators.
- Do not download copyrighted media without appropriate permission from the content owner.
- Users are responsible for complying with the Terms of Service of the respective platforms from which they extract media.

---

## 🤝 Contributing

Contributions are welcome! Please feel free to open an issue or submit a pull request:

1. Fork the repository.
2. Create your feature branch (`git checkout -b feature/amazing-feature`).
3. Commit your changes (`git commit -m 'feat: add some amazing feature'`).
4. Push to the branch (`git push origin feature/amazing-feature`).
5. Open a Pull Request.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE) (or your chosen open source license). Third-party libraries (`yt-dlp`, `instaloader`, `Chaquopy`, `ExoPlayer`, `OkHttp`) are subject to their respective open-source licenses.
