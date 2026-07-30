<div align="center">
  <br/>
  <h1>🔴 Offline-36</h1>
  <p><strong>Decentralized, Serverless, Off-Grid P2P Messenger</strong></p>
  <p>
    <img src="https://img.shields.io/badge/Android-14%2B-3DDC84?logo=android&logoColor=white" alt="Android 14+"/>
    <img src="https://img.shields.io/badge/build-assembleDebug-success?logo=gradle" alt="Build"/>
    <img src="https://img.shields.io/badge/lint-0%20errors-brightgreen" alt="Lint"/>
    <img src="https://img.shields.io/badge/tests-17%2F17%20passed-success" alt="Tests"/>
    <img src="https://img.shields.io/badge/license-Apache%202.0-blue" alt="License"/>
  </p>
  <br/>
</div>

---

**Offline-36** is a fully decentralized, serverless, off-grid peer-to-peer messaging system for Android. Devices discover each other and communicate directly over **local Wi-Fi**, **mesh networks**, or **SMS** — no internet, no servers, no cloud.

> ⚡ **Three transport tiers**: SOSBlue Mesh (UDP broadcast) → F2P Serverless (encrypted engine) → SMS Relay (carrier fallback)

---

## 📋 Table of Contents

- [✨ Features](#-features)
- [🏗 Architecture](#-architecture)
- [📱 Screens](#-screens)
- [🚀 Quick Start](#-quick-start)
- [🛠 Build & Run](#-build--run)
- [🧪 Running Tests](#-running-tests)
- [📁 Project Structure](#-project-structure)
- [🔐 Security](#-security)
- [📄 License](#-license)

---

## ✨ Features

| Feature | Description |
|---------|-------------|
| **🔒 End-to-End Encryption** | AES-256-GCM with phone-derived SHA-256 keys. Every message is encrypted before it leaves the device. |
| **📡 Three Transport Tiers** | SOSBlue Mesh (UDP broadcast), F2P Serverless (Wandering Fibre Engine), SMS Relay (carrier fallback). |
| **🔍 Peer Discovery** | Real UDP heartbeat beacons every 3 seconds. Discovered peers appear in the nearby devices list with IP endpoint tracking for direct unicast. |
| **🌐 Multi-Hop Relay** | TTL-based message forwarding through the mesh. Messages can hop through intermediate devices for extended range. |
| **🔄 Dynamic Network Re-binding** | Automatically detects Wi-Fi network switches and re-binds the UDP socket — no disconnection. |
| **📷 Media Transfer** | Send images and videos through the mesh with chunked transfer and SHA-256 integrity checks. |
| **📰 News Broadcasts** | Broadcast messages to all connected peers via any transport tier. |
| **📱 Conversation Inbox** | Sorted previews with unread badges, transport badge chips (MESH/F2P/SMS), and real-time search. |
| **🔔 Rich Notifications** | Android notification channels with MessagingStyle for 1:1 conversations and group broadcasts. |
| **📲 SMS Relay** | Last-resort carrier fallback with encrypted F2P envelopes over SMS. |
| **💾 Offline Persistence** | Queued packets are stored to disk and flushed when the mesh reconnects. |

---

## 🏗 Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     Offline-36 Android App                       │
│                                                                  │
│  MainActivity (Inbox) ──► ChatActivity ◄── EngineCallback       │
│       │                         │                               │
│       │                         │ sendMessageAsync              │
│       │                         ▼                               │
│       │                    F2PBridge ────────────────────┐       │
│       │                         │        │               │       │
│       │                    dispatchSignal  │  UDP bcast   │       │
│       │                         ▼        ▼               │       │
│       │              WanderingFibreEngine  UdpMeshManager │       │
│       │                         │        │               │       │
│       │                         │   receive              │       │
│       │                         ▼        ▼               │       │
│       │              FibreProcessor → MessageDedup       │       │
│       │                         │        │               │       │
│       │               notifyPacket  routePacket          │       │
│       └─────────────────────────┴────────────────────────┘       │
│                                                                  │
│  NetworkConnectivityManager ── Wi-Fi change → rebind             │
│  WifiDirectManager ────────── P2P fallback                       │
│  PeerDiscoveryHandler ─────── UDP heartbeat                      │
│  SmsTransport ─────────────── Carrier SMS relay                  │
└──────────────────────────────────────────────────────────────────┘
```

### 📦 Key Components

| Component | Package | Role |
|-----------|---------|------|
| **Wandering Fibre Engine** | `f2p-serverless/wandering-fibre-engine/` | Core mesh engine: routing, state machine, encryption, dedup, persistence. |
| **F2PBridge** | `bridge/` | Android ↔ Engine bridge: lifecycle, UDP mesh, Wi-Fi monitoring, P2P fallback. |
| **UdpMeshManager** | `bridge/` | UDP broadcast socket with MulticastLock, dynamic IP tracking, direct unicast. |
| **MessageEncryptor** | `identity/` | AES-256-GCM encryption with phone-derived keys. |
| **MediaChunker** | `media/` | File splitting/reassembly for media transfer with SHA-256 checksums. |
| **ConversationRegistry** | `inbox/` | Thread-safe conversation tracking with unread counts and transport badges. |

---

## 📱 Screens

| Screen | Description |
|--------|-------------|
| **🏠 Inbox (MainActivity)** | Conversation list with search, unread badges, transport pills, and Start New Chat FAB. |
| **💬 Chat (ChatActivity)** | Real-time message bubbles, transport selector, peer discovery panel, attachment button, E2E badge. |
| **📰 News Feed** | Broadcast news feed with compose bar, media attachment, transport selector. |
| **⚙️ Settings** | Transport mode selection, notification toggles, about section. |
| **🔐 Sign In** | Username + phone number registration (E.164). |

---

## 🚀 Quick Start

### Prerequisites

- **Java 11+** (JDK for engine compilation)
- **Android SDK** (API 26+)
- **Gradle 8.x** (wrapped in `SOSBlue/gradlew`)

### Clone & Build

```bash
git clone git@github.com:hasnat-farhan/Offline-36.git
cd Offline-36/SOSBlue
./gradlew assembleDebug
```

The debug APK will be at `SOSBlue/app/build/outputs/apk/debug/app-debug.apk`.

---

## 🛠 Build & Run

```bash
# Debug build
cd SOSBlue && ./gradlew assembleDebug

# Release build (unsigned)
cd SOSBlue && ./gradlew assembleRelease

# Clean build
cd SOSBlue && ./gradlew clean assembleDebug
```

---

## 🧪 Running Tests

### Engine Tests (17 tests)

```bash
cd f2p-serverless

# Compile engine sources
javac -d /tmp/wfe-out $(find wandering-fibre-engine -name '*.java')

# Run all test suites
java -cp /tmp/wfe-out com.antor.f2p.engine.test.IntegrationTest     # 7 tests
java -cp /tmp/wfe-out com.antor.f2p.engine.test.MeshRoutingSimulationTest # 5 tests
java -cp /tmp/wfe-out com.antor.f2p.engine.test.SecurityTest        # 5 tests
java -cp /tmp/wfe-out com.antor.f2p.engine.test.ValidationRunner    # Lifecycle smoke test
```

**Current status:** ✅ 17/17 tests passed | ✅ 0 lint errors | ✅ BUILD SUCCESSFUL

---

## 📁 Project Structure

```
Offline-36/
├── SOSBlue/                              # Android application
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/antor/sosblue/
│   │   │   │   ├── *.java               # Activities, adapters, models
│   │   │   │   ├── bridge/              # F2PBridge, UdpMeshManager, Transports
│   │   │   │   ├── identity/            # UserIdentity, MessageEncryptor, F2PMessage
│   │   │   │   ├── inbox/              # ConversationRegistry, adapter
│   │   │   │   ├── media/              # MediaChunker, transfer listener
│   │   │   │   ├── settings/           # SettingsActivity, SettingsManager
│   │   │   │   ├── news/               # NewsFeed, NewsAdapter, F2PNewsPacket
│   │   │   │   ├── notification/       # NotificationHelper
│   │   │   │   └── util/               # ToastUtils
│   │   │   └── res/                     # Layouts, drawables, values
│   │   └── build.gradle
│   ├── gradlew                          # Gradle wrapper
│   └── HANDOVER.md                      # Full project handover documentation
│
├── f2p-serverless/
│   └── wandering-fibre-engine/          # Wandering Fibre Engine
│       ├── api/                         # Public API (EngineCallback, EngineConfig, etc.)
│       ├── core/                        # Engine loop, state machine, crypto, persistence
│       ├── network/                     # Peer discovery, routing, path computation
│       ├── test/                        # 17 integration/routing/security tests
│       └── README.md                    # Engine documentation
│
├── LICENSE                              # Apache License 2.0
└── README.md                            # This file
```

---

## 🔐 Security

### Encryption

| Layer | Algorithm | Purpose |
|-------|-----------|---------|
| **Payload** | AES-256-GCM | End-to-end message encryption (phone-derived key) |
| **Session** | ECDH (P-256) | Engine-level session key agreement |
| **Key Derivation** | SHA-256 | Phone number → encryption key |

### Key Properties

- **Phone-derived keys**: Each recipient has a unique encryption key derived from their phone number.
- **Targeted decryption**: Messages are locked to the intended recipient — only the target's phone number can decrypt.
- **Self-loop prevention**: Three-layer dedup prevents own broadcasts from being re-processed.
- **Message deduplication**: TTL-based cache (60s, 10k cap) prevents duplicate delivery.

---

## 📄 License

```
Copyright 2026 Hasnat Farhan Ahmed & Ahsanul Haque

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

<div align="center">
  <sub>Built with ❤️ for off-grid communication. No servers. No cloud. Just peer-to-peer.</sub>
</div>
