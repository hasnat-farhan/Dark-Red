# SOSBlue Mesh — Handover Document

**Branch:** `main`
**Last Build:** `assembleDebug` — **BUILD SUCCESSFUL** (11s, verified Jul 30, 2026)
**Engine Tests:** 17/17 passed (7 Integration + 5 Routing + 5 Security — verified Jul 30, 2026)
**Latest Session:** Handover doc refresh & verification (Jul 30, 2026) — re-ran engine tests (17/17 passed), assembled Debug APK (BUILD SUCCESSFUL), updated timestamps; no code changes

---

## Table of Contents

1. [F2P Architectural Blueprint](#1-f2p-architectural-blueprint)
2. [Architecture Overview](#2-architecture-overview)
3. [Complete File Inventory](#3-complete-file-inventory)
4. [New Files Created](#4-new-files-created)
5. [Files Modified](#5-files-modified)
6. [Data Flow](#6-data-flow)
7. [Key Features Implemented](#7-key-features-implemented)
8. [Build & Run](#8-build--run)
9. [Running Tests](#9-running-tests)
10. [Outstanding / Next Steps](#10-outstanding--next-steps)

---

## 1. F2P Architectural Blueprint

### System Philosophy

F2P (Free-to-Peer) Serverless is a decentralized, serverless, off-grid peer-to-peer messaging system built for Android. The core goal is to allow two or more devices to discover each other and communicate directly over local Wi-Fi, mesh networks, or peer-to-peer transports **WITHOUT** relying on a central database, backend server, or cloud signaling system.

### Core Technical Requirements

#### 1.1 User Identity & Key Derivation
- **Registration:** Username + Phone Number (normalized E.164 format)
- **Identity Key:** The normalized `phoneNumber` acts as the primary device identifier and key seed across the local network
- All encryption keys are derived from phone numbers via SHA-256

#### 1.2 Targeted End-to-End Encryption (E2E)
- **Symmetric Encryption:** AES-256-GCM key derived from the **recipient's** phone number
- **Envelope Structure (`F2PMessage`):**
  - `messageId` (UUID) — for deduplication
  - `senderPhone` — normalized sender phone
  - `recipientPhone` — normalized target phone
  - `payload` — AES-256-GCM encrypted message (IV + ciphertext + GCM tag)
  - `timestamp` — millisecond epoch
- **Unlocking / Decryption:**
  - IF `recipientPhone == localUserPhone` → decrypt with own phone key → display
  - IF `recipientPhone != localUserPhone` → message stays locked, relay if TTL > 0

#### 1.3 Dynamic Network & Socket Behavior
- **Self-Loop Filter:** Drop packets where `senderPhone == localUserPhone` OR `messageId` already processed
- **Dynamic Re-binding:** Observe `ConnectivityManager` network changes; close/re-open sockets, re-acquire `MulticastLock`, bind to `0.0.0.0`
- **Transport Fallback:** If LAN broadcast fails across subnets, fall back to Wi-Fi Direct (P2P) or BLE discovery

---

## 2. Architecture Overview

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  SOSBlue Android App                                                         │
│                                                                              │
│  ChatActivity ◄──── EngineCallback ────┐                                     │
│       │                               │                                      │
│       │ sendMessageAsync              │                                      │
│       ▼                               │                                      │
│  F2PBridge ───────────────────────────────────────────────────────┐          │
│       │                    │             │                        │          │
│       │ dispatchSignal     │ UDP bcast   │ NetworkConnectivity    │          │
│       ▼                    ▼             ▼                        │          │
│  WanderingFibreEngine   UdpMeshManager  NetworkConnectivityMgr    │          │
│       │                    │             │                        │          │
│       │                    │ receive     │ Wi-Fi change → rebind  │          │
│       ▼                    ▼             ▼                        │          │
│  FibreProcessor → MessageDeduplicator   WifiDirectManager         │          │
│       │                    │             │ (P2P fallback)         │          │
│       ├─ notifyPacket(local delivery)  ◄──────────────────────────┘          │
│       └─ routePacket(TTL relay)                                              │
│                                                                              │
│  PeerDiscoveryHandler ──► DatagramSocket ──► UDP heartbeat                   │
│       │                                                                      │
│       └─ PeerDiscovery (peer registry)                                       │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Key Layers

| Layer | Location | Responsibility |
|-------|----------|----------------|
| **Android UI** | `SOSBlue/app/src/main/java/com/antor/sosblue/` (top-level) | Chat UI, peer discovery panel, sign-in |
| **Bridge** | `bridge/` | `F2PBridge` (engine ↔ Android), `UdpMeshManager` (UDP socket), `NetworkConnectivityManager` (Wi-Fi monitoring), `WifiDirectManager` (P2P fallback), `TransportMode` (enum) |
| **Identity / Crypto** | `identity/` | `UserIdentity`, `MessageEncryptor` (AES-256-GCM), `F2PMessage` (envelope), `JsonPayloadHelper`, `SignInActivity` |
| **Media** | `media/` | `MediaChunker` (file splitting), `MediaTransferListener` (progress callback) |
| **Engine API** | `f2p-serverless/wandering-fibre-engine/api/` | `WanderingFibreEngine`, `EngineConfig`, `EngineCallback`, `FibrePacket`, `FibreSignal`, `EngineState`, etc. |
| **Engine Core** | `f2p-serverless/wandering-fibre-engine/core/` | `FibreProcessor`, `MessageDeduplicator`, `FibreSecurityHandler` (ECDH), `FibreEngineStateMachine`, `FibreStore`, `EngineLoop`, etc. |
| **Engine Network** | `f2p-serverless/wandering-fibre-engine/network/` | `PeerDiscovery`, `PeerDiscoveryHandler` (UDP heartbeats), `RoutingTable`, `FibrePathRouter`, `LinkMetrics` |

---

## 3. Complete File Inventory

### Android App — `SOSBlue/app/src/main/java/com/antor/sosblue/`

#### Top-level
| File | Purpose |
|------|---------|
| `ChatActivity.java` | Main chat screen — handles sending/receiving F2P encrypted messages, media transfer, peer bar, E2E badge, recipient phone validation |
| `ChatAdapter.java` | RecyclerView adapter — incoming/outgoing text & media bubbles, entrance animations |
| `MainActivity.java` | Dashboard activity — transport mode selector, peer discovery panel, engine lifecycle |
| `MessageModel.java` | Data model for chat messages — supports text + media, sender/recipient phone, content type enum |
| `PeerDevice.java` | Peer device model — id, name, signal strength, connection status, **IP endpoint fields** (added in session) |
| `PeerDiscoveryAdapter.java` | RecyclerView adapter for peer list — dark theme layout, CopyOnWriteArrayList |
| `SOSBlueApplication.java` | Custom Application — guards against isolated-process NPEs |

#### `bridge/`
| File | Purpose |
|------|---------|
| `F2PBridge.java` | **Core bridge** — engine lifecycle, UDP mesh management, signal dispatch, F2P send/receive, media chunking, **NetworkConnectivityManager + WifiDirectManager integration, network change handler, direct ACK sending, peer endpoint tracking** |
| `NetworkConnectivityManager.java` | **NEW** — monitors Wi-Fi network changes via `ConnectivityManager.NetworkCallback` + `BroadcastReceiver`; notifies listeners to re-bind sockets; exposes current local IP |
| `TransportMode.java` | Enum — `SOSBLUE_MESH` / `F2P_SERVERLESS` with SharedPreferences persistence, isolated-process guard |
| `UdpMeshManager.java` | UDP broadcast socket — MulticastLock, `0.0.0.0:41234`, `rebindAfterNetworkChange()`, `sendDirect()`, peer endpoint tracking (`ConcurrentHashMap`), clear stale targets |
| `WifiDirectManager.java` | **NEW** — Wi-Fi Direct P2P discovery and connection; group owner IP exposure; cross-subnet fallback when LAN broadcasts fail |

#### `identity/`
| File | Purpose |
|------|---------|
| `F2PMessage.java` | Binary envelope — serializes/deserializes senderPhone, recipientPhone, encryptedPayload, timestamp, nonce |
| `JsonPayloadHelper.java` | Simple JSON field extractor (no library dependency) |
| `MessageEncryptor.java` | AES-256-GCM encrypt/decrypt — phone-derived SHA-256 keys, key cache, E.164 validation |
| `SignInActivity.java` | Onboarding screen — username + E.164 phone collection |
| `UserIdentity.java` | Persisted identity (SharedPreferences) — `normalizePhoneNumber()`, isolated-process guard |

#### `media/`
| File | Purpose |
|------|---------|
| `MediaChunker.java` | File chunking for large media transfers — split, reassemble, concurrent chunk tracking |
| `MediaTransferListener.java` | Progress callback interface — `onProgress`, `onComplete`, `onFailed` |

#### `util/`
| File | Purpose |
|------|---------|
| `ToastUtils.java` | Toast helper — cancels previous Toast before showing new one, prevents overlay stacking |

### Layout Files (7)
| File | Purpose |
|------|---------|
| `activity_chat.xml` | Chat screen layout — messages list, peer bar, input bar, transport selector, attachment button |
| `activity_main.xml` | Dashboard layout — chat list, peer discovery panel, transport radio group |
| `activity_sign_in.xml` | Sign-in layout — username + phone fields, MaterialButton |
| `item_message_incoming.xml` | Incoming text bubble (white bg, dark text) |
| `item_message_outgoing.xml` | Outgoing text bubble (dark red bg, white text) |
| `item_message_media_incoming.xml` | Incoming media bubble |
| `item_message_media_outgoing.xml` | Outgoing media bubble |
| `item_peer_dark.xml` | Peer device card in peer list |

### Wandering Fibre Engine — `f2p-serverless/wandering-fibre-engine/`

#### `api/`
| File | Purpose |
|------|---------|
| `EngineCallback.java` | Listener interface — signal, packet, state change, error, diagnostics callbacks |
| `EngineConfig.java` | Builder-pattern config — nodeId, heartbeatIntervalMs, maxHeartbeatCycles, discoveryPort |
| `EngineState.java` | Enum — `INITIAL`, `DISCOVERING`, `CONNECTED_MESH`, `ROUTING`, `PAUSED`, `ERROR` |
| `FibrePacket.java` | Packet data class — source, destination, payload, type, sequence number |
| `FibreSignal.java` | Signal data class — id (UUID), type, payload map, timestamp |
| `LogLevel.java` | Enum — `DEBUG`, `INFO`, `WARNING`, `ERROR`, `OFF` |
| `MeshHealthSnapshot.java` | Health metrics — active nodes, avg latency, uptime, queue depth |
| `WanderingFibreEngine.java` | **Main engine class** — lifecycle, routing, peer discovery, dedup, security, telemetry |

#### `core/`
| File | Purpose |
|------|---------|
| `EngineLoop.java` | Main processing loop — signal queue, packet dispatch |
| `FibreEngineStateMachine.java` | State transitions — INITIAL → DISCOVERING → CONNECTED_MESH → ROUTING |
| `FibreLogger.java` | Configurable logging wrapper |
| `FibreProcessor.java` | Packet processing pipeline — signal → encrypt → route; inbound → dedup → decrypt → deliver; TTL relay |
| `FibreSecurityHandler.java` | ECDH key exchange + AES-256-GCM session encryption (separate from F2P phone-derived encryption) |
| `FibreStore.java` | Offline packet queue for when no route is available |
| `MessageDeduplicator.java` | Thread-safe TTL-based dedup cache (ConcurrentHashMap, 60s TTL, 10k cap, background eviction) |
| `PacketBufferPool.java` | Reusable byte buffer pool (reduces GC pressure) |
| `StateMachine.java` | Generic state machine framework |

#### `network/`
| File | Purpose |
|------|---------|
| `FibrePathRouter.java` | Route calculation — finds best path to destination peer |
| `LinkMetrics.java` | Per-link latency, hop count, reliability tracking |
| `PeerDiscovery.java` | Peer registry — `announcePeer()`, `removePeer()`, `clearAllEndpoints()`, `updatePeerEndpoint()`, `evictStalePeers()` |
| `PeerDiscoveryHandler.java` | UDP heartbeat sender/receiver — broadcasts JSON beacons on configurable interval + port |
| `RoutingTable.java` | Routing table — next-hop selection, path diversity |

#### `test/`
| File | Purpose |
|------|---------|
| `IntegrationTest.java` | 7 integration tests — message relay, dedup, multi-hop |
| `MeshRoutingSimulationTest.java` | 5 routing simulation tests — mesh formation, path selection |
| `SecurityTest.java` | 5 encryption/decryption tests — round-trip, wrong-key rejection, tamper detection |
| `ValidationRunner.java` | Test runner — runs all tests, reports pass/fail |

---

## 4. New Files Created

### Session 1 (Initial Implementation)
| File | Purpose |
|------|---------|
| `ChatActivity.java` | Main chat screen |
| `SOSBlueApplication.java` | Isolated-process guard |
| `UdpMeshManager.java` | UDP broadcast socket manager |
| `identity/F2PMessage.java` | E2E message envelope |
| `identity/JsonPayloadHelper.java` | JSON field extractor |
| `identity/MessageEncryptor.java` | AES-256-GCM crypto |
| `identity/SignInActivity.java` | Onboarding UI |
| `identity/UserIdentity.java` | Identity persistence |
| `media/MediaChunker.java` | Media file chunking |
| `media/MediaTransferListener.java` | Transfer progress callback |
| `util/ToastUtils.java` | Toast overlay prevention |
| Layout files (7) | XML layouts for screens and bubbles |

### Session 2 (Network-Switching Fix)
| File | Purpose |
|------|---------|
| `bridge/NetworkConnectivityManager.java` | Wi-Fi network change monitoring — `ConnectivityManager.NetworkCallback` + `BroadcastReceiver`; IP/SSID resolution; listener notification |
| `bridge/WifiDirectManager.java` | Wi-Fi Direct P2P fallback — peer discovery, group formation, group owner IP exposure |

---

## 5. Files Modified

### Session 7 (Current — Verification & Doc Refresh)

No code changes in this session. Re-ran all engine tests (17/17 passed) and `assembleDebug` (BUILD SUCCESSFUL in 11s) to verify the project state. Handover document refreshed with current timestamps.

| File | What Changed |
|------|-------------|
| `HANDOVER.md` | Updated `Last Build` and `Engine Tests` with current verification date; added Session 7 entry; refreshed timestamps throughout |

### Session 6

#### Android Layer
| File | What Changed |
|------|-------------|
| `ChatActivity.java` | **Runtime permission request for Android 11 (Oppo ColorOS):** Added `wifiPermissionLauncher` using `ActivityResultContracts.RequestMultiplePermissions` — requests `ACCESS_FINE_LOCATION` on API 29-32 (required for Wi-Fi Direct on Android 10-12) or `NEARBY_WIFI_DEVICES` on API 33+ (auto-granted on Android 14+). Added `requestWifiPermissionsIfNeeded()` helper called during `onCreate()`. Previously the app declared permissions in the manifest but never requested them at runtime — on Oppo Android 11, `WifiP2pManager.discoverPeers()` threw an uncaught `SecurityException`, crashing the app. **Dead code removal:** Removed unused `PERMISSION_REQUEST_CODE` constant. |
| `F2PBridge.java` | **SecurityException guard:** `wifiDirectManager.startDiscovery()` call (which occurs before the try-catch in `startEngine()`) now wrapped in try-catch — prevents crash when the runtime location permission has not been granted. |
| `WifiDirectManager.java` | **SecurityException guards on all P2P calls:** Wrapped `discoverPeers()`, `requestPeers()`, and `connectToPeer()` in try-catch for `SecurityException` (missing location permission on Android 10-12) and generic `Exception`. The `discovering` flag is reset on failure to allow retry. |
| `UdpMeshManager.java` | **SecurityException guard on MulticastLock:** Wrapped `multicastLock.acquire()` in try-catch — on some OEM ROMs (Oppo ColorOS, MIUI) this can fail without location permission. Sets `multicastLock = null` on failure so subsequent `releaseMulticastLock()` won't NPE. |
| `NetworkConnectivityManager.java` | **SecurityException guard:** Wrapped `registerNetworkCallback()` in try-catch — on some ColorOS builds this can throw `SecurityException` without location permission. |

### Session 5 (Previous)

#### Android Layer
| File | What Changed |
|------|-------------|
| `F2PBridge.java` | **Media sending fix — added UDP broadcast for media chunks:** `sendMediaAsync()` now generates a UUID `message_id` per chunk, registers it in `seenMessageIds` for self-loop prevention, includes `"ttl":"3"` for multi-hop relay, builds a complete UDP JSON payload via new `buildF2pMediaPayload()` helper, and broadcasts it via `udpMeshManager.broadcast(jsonPayload)`. Previously, media chunks were only dispatched as engine signals (which have no actual network send mechanism) — they never reached the wire. Added `escapeJson()` helper for safe JSON string encoding. **Engine tests verified:** All 17/17 tests passed (Integration + Routing + Security). |

### Session 4

#### Engine Layer
| File | What Changed |
|------|-------------|
| `PeerDiscoveryHandler.java` | **Username in heartbeats:** Added `username` parameter to constructor and included `"username"` field in the JSON heartbeat broadcast. The heartbeat now carries the user's display name alongside `node_id`, `phone`, and `timestamp` so receiving devices can show a human-readable name for each discovered peer. |

#### Android Layer
| File | What Changed |
|------|-------------|
| `F2PBridge.java` | **Peer discovery listener interface:** Added `PeerDiscoveryListener` with `onPeerDiscovered(nodeId, username, phone, ip, port)` and `onPeerLost(nodeId)` callbacks. Added `CopyOnWriteArrayList<PeerDiscoveryListener>` with `add/removePeerDiscoveryListener()`. **Heartbeat username extraction:** Parses `"username"` field from heartbeat JSON and passes it to listeners. **ACK-only-for-recipient fix:** `sendMessageAck()` is now only called when the message is addressed to the local device (was previously sent for every received message). **Multi-hop relay re-broadcast:** Added TTL decrement logic — if a received message is NOT for the local device and has TTL>1, it decrements TTL and re-broadcasts over UDP so the next mesh hop can receive it. The existing `seenMessageIds` dedup cache prevents infinite relay loops. **Wi-Fi Direct auto-trigger:** Wi-Fi Direct discovery now starts alongside UDP mesh at initial engine startup (not just after network rebind). |
| `MainActivity.java` | **Real peer discovery:** Registers a `PeerDiscoveryListener` that fills a `ConcurrentHashMap<String, PeerDevice>` from real UDP heartbeats. The peer panel now shows actual discovered devices with their usernames instead of simulated peers. **Peer click → ChatActivity:** Tapping a peer launches `ChatActivity` with `EXTRA_RECIPIENT_PHONE` (for E2E encryption) and `EXTRA_RECIPIENT_NAME` (for display) as intent extras. Phone numbers are stored separately from display names in a `peerPhoneNumbers` map. |
| `ChatActivity.java` | **Intent-based recipient:** Accepts `EXTRA_RECIPIENT_PHONE` (E.164 phone for encryption) and `EXTRA_RECIPIENT_NAME` (display name for title bar) from launching activities. When a peer is selected from MainActivity's discovery panel, the chat title updates to show the peer's name and the recipient phone field is pre-filled with the correct E.164 number. |

### Session 3 (Build-Fix + Key-Derivation Hardening — Previous)

#### Android Layer
| File | What Changed |
|------|-------------|
| `WifiDirectManager.java` | **Fixed `setGroupOwnerIntent(15)` build error:** `setGroupOwnerIntent()` only exists on `WifiP2pConfig.Builder`, not on `WifiP2pConfig` itself. Replaced entire API-level if/else with direct field assignment `config.groupOwnerIntent = 15;` which compiles on all API levels (26+). **Fixed `InetAddress` type mismatch in `handleConnectionInfo()`:** `info.groupOwnerAddress.getAddress()` returns `byte[]`, not `InetAddress`. Changed to `InetAddress addr = info.groupOwnerAddress;` — the field is already an `InetAddress`, call `getHostAddress()` directly on it. |
| `MessageEncryptor.java` | **Strengthened `deriveKey()` normalization:** Previously only stripped leading `+` before SHA-256 hashing. Now also strips all non-digit characters (spaces, dashes, parentheses, dots) before stripping `+`. This ensures consistent keys even if a phone number with formatting characters bypasses `normalizePhoneNumber()` — critical for cross-device interoperability. |

### Session 2 (Network-Switching Fix)

#### Android Layer
| File | What Changed |
|------|-------------|
| `F2PBridge.java` | **Network change handling:** Added `NetworkConnectivityManager` field + wiring; `handleNetworkChange()` re-binds UDP socket, clears dedup cache, starts Wi-Fi Direct discovery. **Peer endpoint tracking:** Updates `UdpMeshManager` with sender IP:port from received datagrams. **Direct ACK:** `sendMessageAck()` sends acknowledgment directly to sender's dynamic IP (not broadcast). **WifiDirectManager** initialized and wired. `postRebindListeners` removed after review. |
| `UdpMeshManager.java` | **`rebindAfterNetworkChange()`:** Closes old socket, releases MulticastLock, clears peer targets, re-acquires lock, opens fresh socket on `0.0.0.0:port`. **`sendDirect()`:** Unicast delivery to specific IP:port (non-broadcast). **Peer endpoint tracking:** `ConcurrentHashMap<String, PeerEndpoint>` with `updatePeerEndpoint()`, `getPeerEndpoint()`, `clearPeerTargets()`. **Rebind listeners:** `addRebindListener()`/`removeRebindListener()` for post-rebind hooks. **Socket made `volatile`** for thread safety. Refactored into `openSocket()` / `closeSocketInternal()` helpers. |
| `PeerDevice.java` | Added `ipAddress` (String), `port` (int), `lastSeenMs` (long) fields; `updateEndpoint()` method; `getEndpoint()` accessor |
| `AndroidManifest.xml` | Added `<uses-feature android:name="android.hardware.wifi.direct" android:required="false" />` |

#### Engine Layer
| File | What Changed |
|------|-------------|
| `PeerDiscovery.java` | Added `clearAllEndpoints()` — sets endpoint to empty string on all peers (for network change); `updatePeerEndpoint(String peerId, String endpoint)` — updates IP without resetting lastSeen; `getPeerEndpoint()`, `hasPeers()`, `peerCount()` accessors |
| `PeerDiscoveryHandler.java` | Added `closeSendSocket()` helper; imported `InetSocketAddress`, `Map`, `ConcurrentHashMap` for future extensibility |

### Session 1 (Initial Implementation)

| File | What Changed |
|------|-------------|
| `AndroidManifest.xml` | Added INTERNET, ACCESS_WIFI_STATE, CHANGE_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE, ACCESS_NETWORK_STATE, ACCESS_FINE_LOCATION, NEARBY_WIFI_DEVICES, BLUETOOTH, BLUETOOTH_ADMIN, BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT |
| `F2PBridge.java` | **Major rewrite** — integrated UdpMeshManager, heartbeat detection, phone normalization, TTL propagation, message_id generation, real PeerDiscoveryHandler wiring |
| `ChatAdapter.java` | Added media message support (image/video thumbnails), view types for incoming/outgoing, entrance animations |
| `MainActivity.java` | IME keyboard insets handling, F2PBridge integration |
| `MessageModel.java` | Added contentType, mediaUri, mediaMimeType, mediaSize fields; AtomicLong ID generation |
| `PeerDiscoveryAdapter.java` | Thread-safe CopyOnWriteArrayList, dark theme layout |
| `TransportMode.java` | F2P_SERVERLESS + SOSBLUE_MESH enum with persistence |
| `colors.xml` | Incoming bubble colors — white bg (`#FFFFFF`), dark text (`#1A1A1A`) |
| `themes.xml` (values + values-night) | Dark Red theme with status bar colors |
| `bubble_incoming.xml` | White background (`#FFFFFF`), light stroke (`#DDDDDD`) |
| `build.gradle` | NDK ABI filters, legacy JNI packaging |
| `proguard-rules.pro` | JNI, CLDR/i18n, SharedPreferences, ListAdapter keep rules |
| `MessageEncryptor.java` | **Key-derivation standardisation** — strips leading `+` before SHA-256; `decrypt()` parameter renamed `senderPhone` → `phoneForDerivation` |
| `F2PBridge.java` (second pass) | Added `seenMessageIds` ConcurrentHashMap + ScheduledExecutorService (60s TTL, 30s cleanup) |
| `ChatActivity.java` | **Encryption mismatch fix** — `decrypt()` called with `myPhone` instead of `senderPhone`. All `Toast.makeText()` → `ToastUtils.showShort()` |
| `MainActivity.java` | All `Toast.makeText()` → `ToastUtils.showShort()` |
| `SignInActivity.java` | Welcome Toast → `ToastUtils.showShort()` |
| `PeerDiscoveryHandler.java` | **Complete rewrite** — from mock to real UDP broadcast heartbeats |
| `FibreProcessor.java` | Added dedup check, TTL relay (decrement & forward), phone normalization in routing |
| `WanderingFibreEngine.java` | `peerDiscoveryHandler` non-final, `setPeerDiscoveryHandler()`/`getPeerDiscoveryHandler()`, `MessageDeduplicator` integration |
| `EngineConfig.java` | Added `discoveryPort` field (default 41234) |

---

## 6. Data Flow

### 6.1 Sending a Message (F2P_SERVERLESS mode)

```
User taps Send
  → ChatActivity.sendCurrentMessage()
     → Validate recipient E.164 phone number
     → F2PBridge.sendMessageAsync(F2P_SERVERLESS)
        → UserIdentity.normalizePhoneNumber(recipient)
        → MessageEncryptor.encrypt(recipientPhone, text)
            → SHA-256(recipientPhone) → AES-256 key
            → Encrypt with AES-256-GCM
            → Prepend 12-byte IV
        → UUID.randomUUID() → message_id
        → Build F2PMessage envelope:
            {senderPhone, recipientPhone, encryptedPayload, timestamp, nonce}
        → dispatchSignal("chat_message", {
            _destination, sender_phone, recipient_phone,
            f2p_envelope (Base64), transport, ttl: "3", message_id})
        → udpMeshManager.broadcast(jsonPayload)  ← LAN broadcast
        ✓ Local delivery via engine.notifyPacket()
```

### 6.2 Receiving a Message

```
UdpMeshManager receives UDP datagram (receiver thread)
  → F2PBridge callback → parse JSON fields
     │
     ├── Type == "peer_heartbeat"?
     │   → Extract node_id, phone, skip own heartbeats
     │   → Update peer endpoint (source IP:port) in UdpMeshManager
     │   → engine.getPeerDiscoveryHandler().onHeartbeatReceived()
     │   → peerDiscovery.announcePeer()
     │   → stateMachine.meshConnected()
     │
     ├── Type == "ack"?
     │   → Log acknowledgment (future: UI delivery confirmation)
     │
     └── Regular message?
         → Check messageId dedup (seenMessageIds)
         → Check sender != localUserPhone
         → Update sender's IP endpoint for future direct ACKs
         → engine.dispatchSignal("chat_message", fields)
            → FibreProcessor.processSignal()
               → Serialise payload → FibrePacket
               → Skip ECDH encryption (F2P already has phone-derived crypto)
               → Process inbound packet:
                  → MessageDeduplicator.trySeen() check
                  → If recipientPhone == localPhone:
                     → Notify packet → ChatActivity renders
                     → Send direct ACK back to sender's IP:port
                  → If TTL > 0: decrement, relay via routing table
```

### 6.3 Network Change (Wi-Fi Switch / IP Change)

```
ConnectivityManager.NetworkCallback fires:
  onAvailable / onLost / onCapabilitiesChanged / onLinkPropertiesChanged
    → NetworkConnectivityManager.handleNetworkChange()
       → Resolve new local IP and SSID
       → Notify listeners (F2PBridge)

F2PBridge.handleNetworkChange(newLocalIp)
  → UdpMeshManager.rebindAfterNetworkChange()
     → Close old socket (interrupt receiver thread)
     → Release MulticastLock
     → Clear peer endpoint cache (IPs are stale)
     → Re-acquire MulticastLock
     → Open new DatagramSocket on 0.0.0.0:41234
     → Start new receiver thread
  → Clear messageId dedup cache (prevent false duplicates)
  → Start Wi-Fi Direct fallback discovery
  → Notify post-rebind listeners:
     → engine.getPeerDiscovery().clearAllEndpoints()
     → wifiDirectManager.startDiscovery()
```

### 6.4 Peer Discovery

```
Engine starts → PeerDiscoveryHandler opens DatagramSocket
  → Every 3 seconds: broadcast JSON heartbeat to 255.255.255.255:41234
     {"type":"peer_heartbeat","node_id":"...","phone":"...","timestamp":...}

Other device's UdpMeshManager receives heartbeat
  → F2PBridge detects "type":"peer_heartbeat"
  → Extracts node_id, phone, skips own heartbeats
  → Updates peer endpoint (source IP:port) for future direct ACKs
  → engine.getPeerDiscoveryHandler().onHeartbeatReceived(nodeId, endpoint)
     → peerDiscovery.announcePeer()
     → stateMachine.meshConnected() (first time)
```

### 6.5 Wi-Fi Direct Fallback (Cross-Subnet)

```
When LAN broadcasts fail (devices on different subnets):
  → WifiDirectManager.startDiscovery() called:
     → WifiP2pManager.discoverPeers()
     → BroadcastReceiver listens for WIFI_P2P_PEERS_CHANGED_ACTION
     → requestPeers() → callback with discovered P2P devices

When a P2P peer is selected:
  → wifiDirectManager.connectToPeer(deviceAddress)
     → WifiP2pManager.connect() → connection + group formation
     → WIFI_P2P_CONNECTION_CHANGED_ACTION → requestConnectionInfo()
     → Extract group owner IP address
     → F2PBridge can now route messages via the P2P link's IP
```

---

## 7. Key Features Implemented

### ✅ Real UDP Peer Discovery
- `PeerDiscoveryHandler` broadcasts real heartbeat beacons every 3 seconds
- Heartbeat includes `node_id`, `phone`, **`username`** (added in Session 4), and `timestamp`
- `UdpMeshManager` receives heartbeats on the same port (41234)
- Self-heartbeat filtering via normalized phone comparison
- Peer endpoint IP:port tracked for direct ACK responses

### ✅ Message Deduplication
- **Two-layer dedup:**
  1. `MessageDeduplicator` (engine core) — thread-safe ConcurrentHashMap with 60s TTL, 10k cap, background eviction
  2. `F2PBridge.seenMessageIds` (UDP layer) — ConcurrentHashMap + ScheduledExecutorService (60s TTL, 30s cleanup)
- Every outgoing message carries a UUID `message_id`
- Dedup cache cleared on network change to prevent false positives

### ✅ TTL Multi-hop Relay
- Outgoing messages carry `"ttl":"3"` in payload (both text and media)
- `FibreProcessor` extracts TTL on inbound — if no recipient match:
  - TTL > 0 → decrement, rebuild payload, forward via routing table
  - TTL = 0 → drop with "TTL expired" log
- **Bridge-side relay re-broadcast** (Session 4): The F2PBridge now re-broadcasts relayed packets over UDP with decremented TTL when the message is not for the local device. The `seenMessageIds` dedup cache prevents infinite relay loops.

### ✅ Phone Number Normalization
- `UserIdentity.normalizePhoneNumber()` strips all non-digits except leading `+`
  - `"+880 1712-345678"` → `"+8801712345678"`
  - `"+1 (555) 123-4567"` → `"+15551234567"`
- Normalized before encryption key derivation, address comparison, and JSON serialization
- Leading `+` stripped before SHA-256 in key derivation so both `+880...` and `880...` produce identical keys
- **Defensive normalization in `deriveKey()`:** Raw phone input is sanitized (non-digit chars stripped) before key derivation, ensuring consistent keys even if a formatting character bypasses the caller-side normalizer

### ✅ End-to-End Encryption
- AES-256-GCM with phone-derived SHA-256 keys
- Key cache (`ConcurrentHashMap`) avoids recomputation
- F2P messages carry their own phone-derived encryption in `f2p_envelope` — engine's ECDH layer is skipped to avoid double encryption
- 12-byte IV prepended to ciphertext; extracted before decryption on receiver side
- Sender encrypts with normalized `recipientPhone`; receiver decrypts with their own `localUserPhone`
- Media chunks are individually encrypted with the same phone-derived key before being split and broadcast

### ✅ Dynamic Network Re-binding
- `NetworkConnectivityManager` detects Wi-Fi network changes via:
  - `ConnectivityManager.NetworkCallback` (API 21+, primary)
  - `BroadcastReceiver` for `WifiManager.NETWORK_STATE_CHANGED_ACTION` (fallback)
- `UdpMeshManager.rebindAfterNetworkChange()`:
  - Closes old DatagramSocket + releases MulticastLock
  - Clears stale peer IP endpoints
  - Re-acquires MulticastLock + opens fresh socket on `0.0.0.0:41234`
  - Starts new receiver thread with same packet listener
- `F2PBridge.seenMessageIds.clear()` prevents false dedup after IP change
- `PeerDiscovery.clearAllEndpoints()` forces re-discovery of peer addresses

### ✅ Transport Fallback: Wi-Fi Direct
- `WifiDirectManager` provides fallback when LAN broadcasts fail across subnets
- Uses `WifiP2pManager` for peer discovery and group formation
- Exposes group owner IP for cross-subnet message routing
- Graceful degradation if Wi-Fi Direct is unavailable on device (`isAvailable()` check)
- P2P receiver registered for `WIFI_P2P_PEERS_CHANGED_ACTION`, `WIFI_P2P_CONNECTION_CHANGED_ACTION`, etc.
- **Auto-triggered on startup** (Session 4): Wi-Fi Direct discovery starts alongside UDP mesh at initial engine startup — not just after network rebind — so devices can be discovered even when guest-network AP isolation blocks UDP broadcasts.

### ✅ Direct ACK to Dynamic IP
- `UdpMeshManager.sendDirect()` unicasts to a specific IP:port (not broadcast)
- `F2PBridge.sendMessageAck()` sends acknowledgment directly back to the sender's source IP:port from the received datagram
- ACK **only sent when the message is addressed to the local device** (not for relayed messages)
- ACK payload includes `message_id`, `recipient_phone`, `acknowledger_phone`, `timestamp`
- Peer endpoints tracked via `ConcurrentHashMap<String, PeerEndpoint>` — updated on every received packet

### ✅ UI Contrast Fix
- **Incoming bubbles:** White (`#FFFFFF`) background with dark text (`#1A1A1A`)
- **Outgoing bubbles:** Dark red (`#D32F2F`) with white text (`#FFFFFF`)
- Keyboard: `adjustResize` + IME insets listener keeps composer above keyboard

### ✅ Permissions
- Full network, Wi-Fi multicast, Bluetooth, and location permissions declared
- `ACCESS_FINE_LOCATION` limited to `maxSdkVersion="32"` (replaced by `NEARBY_WIFI_DEVICES` on 33+)
- `android.hardware.wifi.direct` declared as `required="false"` (app works without it)
- **Runtime permission request (Session 6):** `ACCESS_FINE_LOCATION` (API 29-32) or `NEARBY_WIFI_DEVICES` (API 33+) requested at runtime via `ActivityResultContracts.RequestMultiplePermissions` in `ChatActivity.onCreate()` — fixes crash on Oppo Android 11 (ColorOS) where missing runtime location permission throws `SecurityException` in `WifiP2pManager.discoverPeers()`
- **SecurityException guards (Session 6):** All Wi-Fi Direct calls (`discoverPeers`, `requestPeers`, `connectToPeer`) and `MulticastLock.acquire()` and `NetworkCallback.register()` wrapped in try-catch — even if permission is denied, the app logs a warning and operates in degraded mode without crashing

### ✅ Message Self-Loop Prevention
- **Three-layer protection:**
  1. `messageId` dedup — `seenMessageIds` ConcurrentHashMap (60s TTL, background eviction)
  2. `senderPhone == localUserPhone` comparison (normalized) drops own broadcasts
  3. Engine-level `MessageDeduplicator` catches duplicates from relay paths
- Locally transmitted packets received on loopback/broadcast silently dropped before processing

### ✅ Toast Overlay Fix
- `ToastUtils` helper cancels the previous Toast before showing a new one
- All `Toast.LENGTH_SHORT` calls across `ChatActivity`, `MainActivity`, and `SignInActivity` replaced with `ToastUtils.showShort()`

### ✅ Isolated Process Safety
- `SOSBlueApplication` guards `onCreate()` + `attachBaseContext()` with `Process.isIsolated()`
- `UserIdentity` and `TransportMode` check isolated process before accessing SharedPreferences
- Prevents NPE crashes in Android sandboxed child processes (WebView renderer, etc.)

---

## 8. Build & Run

```bash
# Debug build (signed with debug keystore — installable directly)
cd SOSBlue
./gradlew assembleDebug

# Release build (unsigned — needs keystore signing)
./gradlew assembleRelease

# APK outputs:
#   SOSBlue/app/build/outputs/apk/debug/app-debug.apk
#   SOSBlue/app/build/outputs/apk/release/app-release-unsigned.apk
```

---

## 9. Running Engine Tests

```bash
cd f2p-serverless

# Compile all engine sources
javac -d out $(find wandering-fibre-engine -name '*.java')

# Run tests
java -cp out com.antor.f2p.engine.test.IntegrationTest
java -cp out com.antor.f2p.engine.test.MeshRoutingSimulationTest
java -cp out com.antor.f2p.engine.test.SecurityTest
java -cp out com.antor.f2p.engine.test.ValidationRunner
```

**Last Results:** All 17/17 tests passed (7 Integration + 5 Routing + 5 Security).

---

## 10. Outstanding / Next Steps

| Priority | Task | Notes |
|----------|------|-------|
| **Medium** | Configure signing in `build.gradle` | So `assembleRelease` produces a signed installable APK |
| **Medium** | Android lint sweep | Run `./gradlew lint` to catch deprecation warnings |
| **Low** | Fix manifest permission duplications | `ACCESS_WIFI_STATE` and `CHANGE_WIFI_STATE` are declared twice in `AndroidManifest.xml` |
| **Low** | BLE (Bluetooth Low Energy) discovery | Second transport fallback alongside Wi-Fi Direct for cross-subnet peer discovery |
| **Low** | JUnit unit tests for `MessageDeduplicator` | Concurrent access, TTL eviction, capacity limits |
| **Low** | Peer eviction with TTL | Remove stale peers from `PeerDiscovery` if heartbeat not received within N seconds; also clear from `UdpMeshManager.peerEndpoints` |
| **Low** | Per-peer message sequencing | Add sequence counter for reliable ordering in chat |
| **Low** | Real-time connection status | Show online/offline indicators per peer based on heartbeat freshness |
| **Low** | Move `messageId` into binary `F2PMessage` envelope | Currently at JSON payload level; blueprint specifies it inside the binary envelope |
| **Low** | Unit tests for `NetworkConnectivityManager` | Test re-binding flow, listener notification, IP resolution |
| **Low** | Unit tests for `WifiDirectManager` | Test discovery lifecycle, connection handling (requires emulator with P2P support) |

### ✅ Completed (Recent Sessions)

| Fix | Description |
|-----|-------------|
| **Encryption key-derivation standardisation** | Leading `+` stripped before SHA-256; `decrypt()` takes `phoneForDerivation`; ChatActivity decrypts with `myPhone` instead of `senderPhone` |
| **Message self-loop prevention** | `ConcurrentHashMap` messageId cache + sender-phone check in `F2PBridge` UDP callback |
| **Toast overlay fix** | `ToastUtils` cancels previous Toast before showing new one |
| **Network-switching socket death fix** | `NetworkConnectivityManager` monitors Wi-Fi changes; `UdpMeshManager.rebindAfterNetworkChange()` closes/re-opens socket, re-acquires MulticastLock, clears peer cache |
| **Sub-network communication fallback** | `WifiDirectManager` provides Wi-Fi Direct P2P discovery + connection when LAN broadcasts fail |
| **Direct ACK to dynamic IP** | `sendDirect()` unicasts to sender's IP:port; `sendMessageAck()` sends acknowledgment using the dynamic peer endpoint from received datagram |
| **Stale peer endpoint cleanup** | `UdpMeshManager.clearPeerTargets()` + `PeerDiscovery.clearAllEndpoints()` called on network change |
| **Thread safety fixes** | `UdpMeshManager.socket` made `volatile`; `WifiDirectManager.shutdown()` wrapped in try-catch; dead code removed |
| **WifiDirectManager build-error fixes** | `config.setGroupOwnerIntent(15)` → `config.groupOwnerIntent = 15` (field assignment); `InetAddress` type mismatch in `handleConnectionInfo()` — `getAddress()` returns `byte[]`, now uses the field directly |
| **E2E key-derivation hardening** | `MessageEncryptor.deriveKey()` now normalizes phone numbers (strips all non-digit chars) before SHA-256, preventing key mismatches from formatted input |
| **Media sending fix — UDP broadcast** | `F2PBridge.sendMediaAsync()` now broadcasts media chunks over UDP (was only dispatching engine signals). Added `message_id`, `ttl`, `buildF2pMediaPayload()` and `escapeJson()` helpers. Photos now actually reach the receiving device. |
| **Android 11 crash fix (Oppo ColorOS)** | Added runtime permission request for `ACCESS_FINE_LOCATION`/`NEARBY_WIFI_DEVICES` in `ChatActivity`; wrapped all Wi-Fi Direct P2P calls (`discoverPeers`, `requestPeers`, `connectToPeer`), `MulticastLock.acquire()`, and `NetworkCallback.register()` in `SecurityException` try-catch guards. App no longer crashes on Oppo/Android 11. |
