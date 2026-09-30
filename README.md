# netinfo (v1.3.0)

**netinfo** is a modern, lightweight, and privacy-respecting network diagnostics utility for Android. Built entirely with **Kotlin** and **Jetpack Compose (Material 3)**, it provides real-time telemetry and signal metrics for Wi-Fi and Cellular (Mobile) connections, as well as wired Ethernet and VPN interfaces.

---

## 📱 Features

### 1. Wi-Fi Diagnostics
* **Signal Strength**: Real-time signal measurement in dBm and percentage with visual circular gauge and rating scale (*Excellent*, *Good*, *Fair*, *Weak*).
* **Network Identification**: Network SSID detection with clean formatting and location permission detection.
* **Frequency & Band**: Automatic detection of 2.4 GHz, 5 GHz, and 6 GHz (Wi-Fi 6E/7) radio frequency bands.
* **Link Speed**: Current link throughput speed in Mbps.
* **IP Configuration**: Local IPv4 address with one-tap clipboard copy and Default Gateway IP address.

### 2. Cellular (Mobile Network) Monitoring
* **Carrier & SIM Detection**: Active mobile network operator name and SIM card status.
* **Network Standard & Generation**: Live detection of cellular transmission standards: 2G (GPRS/EDGE), 3G (UMTS/HSPA+), 4G/LTE, and 5G NR.
* **Signal Quality**: Transmitter signal level in dBm with a responsive linear diagram scale.
* **Roaming Status**: Real-time detection of national and international roaming.

### 3. Ethernet & System Network Detection
* **Broad Transport Support**: Built-in awareness of Ethernet (LAN) and VPN network states, preventing false offline warnings in Android emulators or Android TV/box setups.
* **Instant Offline Mode**: Clear status banner when all connectivity is lost, safely preserving the last known network parameters.

### 4. Connection Change History
* **Local Persistence (Room Database)**: Automatically logs up to 200 state transitions (connections, disconnections, SSID shifts, mobile carrier changes, and offline events).
* **Indexed Timestamps**: Rapid chronological event inspection with connection type badges and signal ratings.
* **Data Management**: Clear history anytime with a single tap.

### 5. UI & User Experience
* **Material Design 3**: Clean, minimalist layout adhering strictly to M3 guidelines and spacing grids.
* **Theme Switching**: Instant toggle between Dark Mode and Light Mode.
* **Bilingual Support**: Real-time switch between English (EN) and Polish (PL).
* **Hardware Back Navigation**: Native `BackHandler` integration for sub-tab navigation.
* **Coffee Support**: Quick shortcut to support the creator at [cuplink.to/sansedro](https://cuplink.to/sansedro).

---

## 🛠️ Architecture & Reliability

* **ANR-Free Architecture**: Implements a dedicated debouncing pipeline and thread synchronization with `kotlinx.coroutines.sync.Mutex`, preventing Binder thread pool exhaustion from rapid `ConnectivityManager` callbacks.
* **Background Isolation**: All heavy IPC queries (`TelephonyManager`, `WifiManager`, network interface scans) execute strictly on `Dispatchers.IO` with comprehensive error boundary fallbacks (`Throwable` trapping).
* **Modern Android APIs**: Utilizes `NetworkCapabilities.transportInfo` on Android 10+ (Q-V) while preserving backwards compatibility down to Android 7.0 (API 24).
* **Dynamic Permission Handling**: Integrates `LifecycleEventObserver` (`ON_RESUME`) to automatically re-evaluate and apply permissions granted via system Settings without requiring an app restart.

---

## 🔒 Permissions & Privacy

netinfo processes **100% of data locally on your device**. No telemetry, analytics, or external server calls are made.

| Permission | Purpose |
| :--- | :--- |
| `ACCESS_FINE_LOCATION` | Required by Android to read the Wi-Fi SSID and transmitter cell signal strength. |
| `ACCESS_COARSE_LOCATION` | Recommended by Android 12+ alongside fine location for approximate location selection. |
| `READ_PHONE_STATE` | Required to query carrier name, data network type (4G/5G), and roaming status. |
| `ACCESS_NETWORK_STATE` | Required to monitor active networks and bandwidth capabilities. |
| `ACCESS_WIFI_STATE` | Required to query Wi-Fi link speed, frequency, and gateway details. |

---

## 🚀 Building & Running

### Requirements
* **Android Studio** (Ladybug / Jellyfish or newer)
* **JDK 17+**
* **Min SDK**: 24 (Android 7.0 Nougat)
* **Target SDK**: 36 (Android 15+)

### Build Commands
```bash
# Build the debug APK
gradle assembleDebug

# Run unit and Robolectric tests
gradle testDebugUnitTest
```

The compiled APK will be generated at:
```
.build-outputs/app-debug.apk
```

---

## 👤 Credits

* **Idea & Creator**: Sansedro ([sansedro@gmail.com](mailto:sansedro@gmail.com))
* **Support**: [Buy me a coffee on cuplink.to](https://cuplink.to/sansedro)
* **Powered by**: Gemini via Google AI Studio
