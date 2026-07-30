# SOSBlue Mesh — Handover Document

**Branch:** `main`
**Last Build:** `assembleDebug` — **BUILD SUCCESSFUL** (verified Jul 30, 2026)
**Lint:** 183 warnings, **0 errors** (down from 221)
**Engine Tests:** 17/17 passed (7 Integration + 5 Routing + 5 Security — verified Jul 30, 2026)
**Latest Session:** SMS Permission Checks, F2PBridge Lambda Crash Fix & Mode-Switch Buffering Dialog (Jul 30, 2026)

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
| `MainActivity.java` | Conversation inbox — clean RecyclerView of recent chats with search, RSS feed icon, overflow menu (News Feed + Settings). No transport controls, no composer, no engine lifecycle — those live in ChatActivity |
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

#### `settings/`
| File | Purpose |
|------|---------|
| `SettingsManager.java` | **NEW** — SharedPreferences-backed notification toggles (chat + news) with `resetToDefaults()` |
| `SettingsActivity.java` | **NEW** — Settings screen with transport mode selection, notification toggles, about section |

#### `util/`
| File | Purpose |
|------|---------|
| `ToastUtils.java` | Toast helper — cancels previous Toast before showing new one, prevents overlay stacking |

### Layout Files (11)
| File | Purpose |
|------|---------|
| `activity_chat.xml` | Chat screen layout — messages list, peer bar, input bar, transport selector, attachment button. Bottom nav **removed** in Session 11 |
| `activity_main.xml` | **Cleaned (Session 13)** — Pure conversation inbox layout. Only top bar (app icon, title, search, RSS, menu) + conversation RecyclerView + empty state. All composers, transport controls, peer discovery panels removed |
| `activity_settings.xml` | Settings layout with transport mode, notification toggles, about info |
| `activity_sign_in.xml` | Sign-in layout — username + phone fields, MaterialButton |
| `item_message_incoming.xml` | Incoming text bubble (white bg, dark text) |
| `item_message_outgoing.xml` | Outgoing text bubble (dark red bg, white text) |
| `item_message_media_incoming.xml` | Incoming media bubble |
| `item_message_media_outgoing.xml` | Outgoing media bubble |
| `item_peer_dark.xml` | Peer device card in peer list |
| `item_conversation.xml` | **NEW** — Conversation inbox card with avatar circle, name, preview text, timestamp, unread badge, and colored transport badge chip (MESH/F2P/SMS) |
| `item_news_card.xml` | **NEW** — News broadcast card with author, transport badge, timestamp, text body, media indicator |

### Menu Files
| File | Purpose |
|------|---------|
| `menu/top_app_bar_menu.xml` | **Further simplified (Session 17)** — Overflow menu with only How SOSBlue Works (menu_about) and Settings (menu_settings). News Feed removed from menu — RSS icon still provides quick access |

### `inbox/`
| File | Purpose |
|------|---------|
| `ConversationModel.java` | **NEW** — Data class with display name, phone, last message preview, timestamp, unread count, has-media flag, avatar char, relative time formatter |
| `ConversationRegistry.java` | **NEW** — Static `ConcurrentHashMap`-based registry (thread-safe) — `update()`, `getAll()` (sorted by newest first), `markRead()`, `clearAll()` |
| `ConversationAdapter.java` | **NEW** — `ListAdapter` with `DiffUtil`, click listener, unread badge display, media indicator icon, transport badge chip (MESH blue, F2P green, SMS blue-grey), avatar circle with first-letter fallback |

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

### Session 22 (SMS Permission Checks, F2PBridge Lambda Crash Fix & Mode-Switch Buffering Dialog)
| File | Purpose |
|------|---------|
| *No new files* | All changes in existing files |

### Session 21 (Media Download — Save Received Media to Gallery)
| File | Purpose |
|------|---------|
| `drawable/ic_download.xml` | **NEW** — Vector drawable: downward arrow into a tray, used as the download button overlay on incoming media bubbles |

### Session 11 (UI/UX Navigation Redesign & Lint Resolution)
| File | Purpose |
|------|---------|
| `menu/top_app_bar_menu.xml` | **NEW** — Overflow menu resource with structured navigation: Chats, News Feed, Transport Mode submenu (3 tiers), Settings, About. Replaces deleted `bottom_nav_menu.xml` |

### Session 10 (Conversation Inbox & Lint Cleanup)
| File | Purpose |
|------|---------|
| `inbox/ConversationModel.java` | Data class for conversation previews — display name, phone, last message text, timestamp, unread count, media indicator, avatar char |
| `inbox/ConversationRegistry.java` | Static thread-safe registry (`ConcurrentHashMap`) — `update()` creates/updates entries, `getAll()` returns sorted by newest message, `markRead()` resets unread count |
| `inbox/ConversationAdapter.java` | RecyclerView `ListAdapter` with `DiffUtil`, per-item click listener, unread badge (circular red), media attachment indicator, relative timestamp |
| `layout/item_conversation.xml` | `MaterialCardView` with: circular avatar (first letter, red bg), display name + timestamp top row, last message preview + media indicator middle, unread badge bottom-right |

### Session 9 (Settings Screen, Notification Toggles, About Info)
| File | Purpose |
|------|---------|
| `settings/SettingsManager.java` | SharedPreferences-backed notification toggles (chat + news) with `resetToDefaults()` |
| `settings/SettingsActivity.java` | Full settings screen — transport mode radio group, notification toggle switches, about section with version info |
| `drawable/ic_settings.xml` | Gear/cog icon for the settings top bar |
| `layout/activity_settings.xml` | Scrollable card-based layout with 3 sections (transport, notifications, about) |

### Session 8 (Broadcast News Feed, Notifications, Persistence)
| File | Purpose |
|------|---------|
| `news/F2PNewsPacket.java` | News broadcast data class — TransportType enum, SMS wire format `[NEWS:AuthorName] text`, Base64 media support |
| `news/NewsFeedActivity.java` | News broadcast feed — card-based RecyclerView, 3-tier transport selector, compose bar with media attachment, search, overflow menu, bottom nav |
| `news/NewsAdapter.java` | News card adapter — ListAdapter with DiffUtil, author/transport badge/timestamp/text/media, entrance animations, read/unread alpha |
| `notification/NotificationHelper.java` | Notification manager — 2 high-priority channels, MessagingStyle 1:1 + group conversations, phone→name display cache, per-sender/per-group history with SharedPreferences persistence |
| `menu/bottom_nav_menu.xml` | Bottom navigation menu — Chats and News tabs |
| `drawable/ic_notification_chat.xml` | Chat notification small icon (white bubble) |
| `drawable/ic_notification_news.xml` | News notification small icon (white bars) |
| `drawable/ic_rss.xml` | RSS/broadcast icon for top bar |
| `drawable/ic_bottom_chat.xml` | Bottom nav Chats tab icon |
| `drawable/ic_bottom_news.xml` | Bottom nav News tab icon |

### Session 7 (Verification & Doc Refresh)
No code changes — re-ran engine tests (17/17 passed) and `assembleDebug` (BUILD SUCCESSFUL).

### Session 6 (Network-Switching Fix)
| File | Purpose |
|------|---------|
| `bridge/NetworkConnectivityManager.java` | Wi-Fi network change monitoring — `ConnectivityManager.NetworkCallback` + `BroadcastReceiver`; IP/SSID resolution; listener notification |
| `bridge/WifiDirectManager.java` | Wi-Fi Direct P2P fallback — peer discovery, group formation, group owner IP exposure |

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

---

## 5. Files Modified

### Session 22 (SMS Permission Checks, F2PBridge Lambda Crash Fix & Mode-Switch Buffering Dialog)

| File | What Changed |
|------|-------------|
| `SmsTransport.java` | **Added runtime permission check & SIM verification before SMS send** — `sendEnvelope()` now checks `ContextCompat.checkSelfPermission(SEND_SMS)` and `TelephonyManager.getSimState() == SIM_STATE_READY` before attempting SMS operations. Fails early with clear error message if permission revoked or SIM absent. All operations wrapped in try-catch for graceful degradation. |
| `TransportMode.java` | **Enhanced hasTelephony() with SEND_SMS permission fallback** — Added SEND_SMS runtime permission fallback (trusts the user's intent). Scoped `SmsManager.getDefault()` guard to pre-API 23 to avoid false blame. All checks wrapped in try-catch. |
| `F2PBridge.java` | **Wrapped dedupCleanup scheduled task in try-catch** — `scheduleAtFixedRate` lambda now has try-catch to prevent unhandled exceptions from killing the scheduler thread. |
| `ChatActivity.java` | **Major mode-switch dialog rewrite:**
  - **Replaced simple bufferingProgress spinner** with non-cancelable `AlertDialog` (`showModeSwitchDialog()` / `dismissModeSwitchDialog()`)
  - **`showModeSwitchDialog()`** — Creates dialog with "Switching Communication Mode…" title, "Please wait…" message, indeterminate ProgressBar spinner tinted red (`primary_red`). Stores spinner reference in `modeSwitchSpinner` for later replacement.
  - **`dismissModeSwitchDialog(boolean success)`** — New signature with success feedback:
    - `success=true`: Changes title to "✓ Connected", message to "{modeLabel} ready", replaces spinner with green checkmark, auto-dismisses after **600ms**
    - `success=false`: Immediate dismiss (failure/timeout/cleanup)
  - **1.5s timeout safety net** — `modeSwitchTimeoutRunnable` posted with `postDelayed`; on timeout dismisses dialog and shows Snackbar
  - **`modeSwitchTimeoutHandler` / `modeSwitchTimeoutRunnable` fields** — For timeout lifecycle management
  - **`modeSwitchSpinner` field** — Kept for spinner→checkmark replacement on success
  - **Bug fix**: Moved `isModeSwitching = false` from `finally` block into `dismissModeSwitchDialog()` — fixes timeout guard for async F2P mode (finally ran before async engine started, making timeout's `if (isModeSwitching)` check useless)
  - **onDestroy safety net** — Added `dismissModeSwitchDialog(false)` to dismiss dialog if activity destroyed mid-transition
  - **All 10 call sites updated** — Success paths (Mesh/SMS/F2P started/engine already running) → `dismissModeSwitchDialog(true)`. Error/timeout/cleanup paths → `dismissModeSwitchDialog(false)`
  - Removed redundant `isModeSwitching = false` from timeout runnable (already handled inside `dismissModeSwitchDialog`) |

### Session 19-20 (Media Chunk Integrity, Bluetooth/Wi-Fi Permissions & Crash Prevention Buffering)

| File | What Changed |
|------|-------------|
| `MediaChunker.java` | **Added public `computeChecksum(byte[])`** — Exposes the internal `sha256()` method so `ChatActivity.handleMediaChunk()` can compute and pass a real checksum instead of an empty byte array for received media chunks. Fixes chunk integrity validation. |
| `ChatActivity.java` | **Major crash prevention overhaul:** Added `isModeSwitching` flag + `currentTransportMode` field to prevent re-entrant mode switch calls. Rewrote `onTransportModeChanged()` with try-catch-finally, null-safe guards on all view references, and engine stop when leaving F2P mode (fixes stale-engine leak). Added `meshPermissionLauncher` field + registration requesting `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` (API 31+) or legacy `BLUETOOTH`/`BLUETOOTH_ADMIN`. Added `requestMeshPermissionsIfNeeded()` method. Calls `requestMeshPermissionsIfNeeded()` from `onTransportModeChanged()` for SOSBLUE_MESH mode. Added buffering progress spinner for ALL mode transitions. Added null-safe try-catch in `refreshPeerBar()`, `sendCurrentMessage()`, and `onMediaSelected()`. Fixed duplicate `requestWifiPermissionsIfNeeded()` declaration and duplicate permission launcher blocks. |
| `ConversationAdapter.java` | **Removed unused import** — Removed `ContextCompat` import flagged by code review. |

### Session 18 (Transport Badge on Inbox Conversation Cards)

| File | What Changed |
|------|-------------|
| `item_conversation.xml` | **Added transport badge chip** — New `@+id/transportBadge` TextView placed between display name and timestamp in Row 1. Styled as a small chip: 18dp height, 9sp bold white text, `paddingHorizontal="6dp"`, `letterSpacing="0.05"`. Hidden by default (`visibility="gone"`). |
| `ConversationAdapter.java` | **Added transport badge binding** — `bind()` now reads `getLastTransportMode()` and displays a colored chip: "MESH" (blue `#2196F3`), "F2P" (green `#388E3C`), "SMS" (blue-grey `#455A64`). Uses `GradientDrawable` with rounded corners for a pill shape. Added `setBadgeStyle()` helper. |

### Session 17 (Menu Simplification + How SOSBlue Works Dialog)

| File | What Changed |
|------|-------------|
| `menu/top_app_bar_menu.xml` | **Replaced News Feed with About** — `menu_news_feed` replaced with `menu_about` ("How SOSBlue Works"). Menu now has 2 items: About and Settings. News Feed is still accessible via RSS icon in top bar. |
| `strings.xml` | **Rewrote about message** — `dialog_about_message` expanded with comprehensive 5-section "how this app works" explanation covering: identity (phone-based E2E), 3 transport tiers (Mesh, F2P, SMS), peer discovery (UDP heartbeats), news broadcasts, and offline-first philosophy. `menu_about` string updated to "How SOSBlue Works". |
| `ChatActivity.java` | **About dialog restored** — Changed overflow menu handler from `menu_news_feed` (opening NewsFeedActivity) to `menu_about` (showing about dialog). Added `AlertDialog.Builder`-based `showAboutDialog()` method using the comprehensive `dialog_about_message` string resource. |
| `MainActivity.java` | **Same overflow menu update** — Changed `menu_news_feed` → `menu_about` handler. Added `showAboutDialog()` with AlertDialog. |
| `NewsFeedActivity.java` | **Same overflow menu update** — Changed `menu_news_feed` → `menu_about` handler. Added `showAboutDialog()` with AlertDialog. |

### Session 16 (SMS Broadcast to SMS Contacts Only)

| File | What Changed |
|------|-------------|
| `ConversationModel.java` | **Added `lastTransportMode` field** — New String field tracking which transport was used for the last message ("SOSBLUE_MESH", "F2P_SERVERLESS", or "SMS_FALLBACK"). Defaults to "SOSBLUE_MESH". Added getter `getLastTransportMode()` and setter `setLastTransportMode()`. |
| `ConversationRegistry.java` | **Added `transportMode` param to `update()`** — New 8th parameter `@NonNull String transportMode` passed through to `ConversationModel.setLastTransportMode()` on both create and update paths. Javadoc updated. |
| `ChatActivity.java` | **3 call sites updated to pass transport mode** — (1) Incoming mesh/F2P: passes `isMeshBroadcast ? "SOSBLUE_MESH" : "F2P_SERVERLESS"`. (2) Incoming SMS: passes `"SMS_FALLBACK"`. (3) Outgoing send: passes `mode.name()` (the currently selected transport). Moved `TransportMode mode` definition before registry update to resolve scope. |
| `NewsFeedActivity.java` | **SMS broadcast now targets SMS contacts** — Instead of sending to selfPhone (demo placeholder), the SMS broadcast case now queries `ConversationRegistry.getAll()`, filters conversations where `lastTransportMode == "SMS_FALLBACK"`, and sends the news to each SMS contact. Tracks sent/failed counts per recipient and shows summary toast (e.g. "News sent to 3 SMS contacts"). Shows helpful message if no SMS contacts exist yet. |

### Session 15 (Crash Audit & Snackbar NPE Protection)

| File | What Changed |
|------|-------------|
| `ChatActivity.java` | **Global crash shield in `onCreate()`** — extracted all initialisation into `initializedOnCreate()` wrapped in a try-catch. Any startup exception is now logged and surfaced to the user via Toast instead of silently crashing to home screen. **6 Snackbar NPE paths guarded** — all `Snackbar.make(findViewById(R.id.root), ...)` calls in async callbacks (Wi-Fi permission callback, SMS permission callback, `showSmsError()`, F2P engine error, send failure, media send failure) now check `isActivityDestroyed` and null-check `findViewById(R.id.root)` before showing. This prevents crashes when permission dialogs or bridge callbacks fire after the activity is destroyed. |
| `activity_main.xml` | **Replaced FAB with ExtendedFloatingActionButton** — now shows "Start New Chat" text + compose icon instead of icon-only. Uses `app:icon="@drawable/text"` and `android:text="@string/start_new_chat"` with white text on red background. |
| `MainActivity.java` | **Added try-catch wrapper** around FAB click handler to safely handle any `startActivity()` failures. |
| `strings.xml` | **Added `start_new_chat`** string resource. |

### Session 14 (New Chat FAB + Home Screen Polish)

| File | What Changed |
|------|-------------|
| `activity_main.xml` | **Added FloatingActionButton** (`@+id/newChatFab`) at bottom-right with `layout_gravity="bottom|end"`, 56dp, `@drawable/text` compose icon, `@color/primary_red` background tint, 20dp margin. **Increased RecyclerView `paddingBottom`** from 0dp to 80dp to prevent the FAB from overlapping the last conversation card. Empty state text updated from "No conversations yet" to "No conversations yet\n\nTap + to start a new chat". |
| `MainActivity.java` | **Added FAB click handler** — `findViewById(R.id.newChatFab).setOnClickListener()` opens `ChatActivity` with no extras (empty recipient field), allowing the user to type any phone number to start a new 1-on-1 conversation. **Removed unnecessary try-catch wrapper** in `onDestroy()` (was logging nothing). |

### Session 13 (UI/UX Refactoring: Clean Inbox & Streamlined Navigation)



| File | What Changed |
|------|-------------|
| `activity_main.xml` | **COMPLETELY REWRITTEN** — Stripped down to a pure conversation inbox. Removed ALL: message composers (`inputMessage`, `inputImageURL`, `inputVideoURL`), send buttons (`sendButton`, `sendButtonContainer`), transport radio group (`transportScroll`, `transportRadioGroup`, `rb_sosblue_mesh`, `rb_f2p_serverless`, `rb_sms_fallback`), reply preview (`replyPreviewContainer`, `textReplyTitle`, `textReplyMessage`, `closeReplyButton`), peer discovery panel (`peerDiscoveryPanel`, `peerRecyclerView`, `peerEmptyHint`, `peerCountLabel`, `peerRefreshButton`), loading containers (`loadingContainer`, `sendProgressBar`), and unused icons (`downIcon`, `unseenMsg`, `switchInputImage`, `chunkCount`). Now only: simplified top bar (app icon + title + inline search overlay + RSS icon + overflow menu) + conversation RecyclerView + empty state text. |
| `MainActivity.java` | **COMPLETELY REWRITTEN** — From 400+ lines of dashboard/transport/engine/peer-discovery code to ~200 lines of pure inbox. Removed: `F2PBridge` init, engine startup (`startEngineAsync`), peer discovery listener, `PeerDiscoveryAdapter`, `discoveredPeers`/`peerPhoneNumbers` maps, `transportRadioGroup`/`radioIdToTransportMode()`/`onTransportModeChanged()`, `showPeerPanel()`/`refreshPeerList()`, `showLoading()`, `showAboutDialog()`, `peerPanel`/`peerRecyclerView`/`peerCountLabel`/`peerEmptyHint` fields, `RadioGroup` import, `EngineConfig` import, `Snackbar` import, `ToastUtils` import, `ConcurrentHashMap` import. Kept: identity gate, `POST_NOTIFICATIONS` permission request, `ConversationAdapter`/`ConversationRegistry`, search filter via `TextWatcher`, simplified overflow menu (only `menu_news_feed` and `menu_settings`), `onResume()` refresh. |
| `menu/top_app_bar_menu.xml` | **REWRITTEN** — Simplified from 6 items (Chats, News Feed, Transport Mode submenu with 3 radio options, Settings, About) to just 2 items: News Feed and Settings. Transport mode selection now happens exclusively via the inline `RadioGroup` inside `ChatActivity`. |
| `item_conversation.xml` | **POLISHED** — Avatar circle increased to 46dp with 20sp bold white initial. Name font increased to 16sp bold, timestamp to 11sp, last message to 13sp. Card padding increased to 14dp, corner radius to 14dp. Unread badge now uses `backgroundTint="@color/primary_red"` for consistent red pill. Removed unnecessary nested `LinearLayout` layers. `MaterialCardView` stroke color `#2A2A2E` kept for subtle dark theme dividers. |
| `ChatActivity.java` | **Overflow menu simplified** — Removed all handlers for removed menu items: `menu_chats`, `menu_transport_mesh`, `menu_transport_f2p`, `menu_transport_sms`, `menu_about`. Now only handles `menu_news_feed` and `menu_settings`. **Removed dead code** — `showAboutDialog()` private method was unreferenced after menu simplification. |
| `NewsFeedActivity.java` | **Overflow menu simplified** — Removed transport mode marking code (`popup.getMenu().findItem(R.id.menu_transport_*).setChecked()`) and all removed menu item handlers. Now only handles `menu_news_feed` and `menu_settings`. **Removed dead code** — `showAboutDialog()` private method. |

### Session 12 (Crash Prevention & Android 14 Stability Fix)

| File | What Changed |
|------|-------------|
| `AndroidManifest.xml` | **Moved MAIN/LAUNCHER intent filter** from `ChatActivity` to `MainActivity`. `ChatActivity` now `exported=false`; `MainActivity` now `exported=true` with launcher intent filter. This was the root cause of the app opening directly to a 1-on-1 chat screen instead of the conversation inbox. |
| `SignInActivity.java` | **Changed `launchChat()`** to launch `MainActivity` instead of `ChatActivity` — users now land on the conversation inbox after sign-in. Updated import from `ChatActivity` to `MainActivity`. |
| `MainActivity.java` | **Added identity gate redirect** at top of `onCreate()` — redirects to `SignInActivity` if not registered. **Added `POST_NOTIFICATIONS` runtime permission request** on Android 13+. **Added imports:** `Manifest`, `PackageManager`, `Build`, `ContextCompat`, `ActivityResultLauncher`, `ActivityResultContracts`, `UserIdentity`. |
| `ChatActivity.java` | **Added `POST_NOTIFICATIONS` runtime permission request** on Android 13+. **Fixed overflow menu** — "Chats" now opens `MainActivity` (inbox) instead of being a no-op. |
| `NewsFeedActivity.java` | **Fixed overflow menu** — "Chats" now opens `MainActivity` (inbox) instead of `ChatActivity`. |
| `SOSBlueApplication.java` | **Added global uncaught exception handler** — logs the crash with device model + SDK version, then delegates to the default handler so crashes are visible in logcat instead of silently killing the app. |
| `WifiDirectManager.java` | **Added `RECEIVER_EXPORTED` flag** to `registerReceiver(p2pReceiver, filter)` — required on Android 14+. Without this flag, the call throws `SecurityException` and instantly crashes the app on API 34+ devices. |
| `NetworkConnectivityManager.java` | **Added `RECEIVER_EXPORTED` flag** to both `registerReceiver(wifiStateReceiver)` and `registerReceiver(networkStateReceiver)` — fixes the same Android 14+ `SecurityException` crash. |

### Session 11 (UI/UX Navigation Redesign, Lint Resolution & Crash Audit)

| File | What Changed |
|------|-------------|
| `activity_main.xml` | **Removed** `BottomNavigationView` (Chats/News tab bar). **Fixed** `InefficientWeight` / `NestedWeights` — changed `layout_width="wrap_content"` → `"0dp"` for weighted `titleContainer` and inner `LinearLayout`. **Fixed** `SmallSp` — bumped timestamp font from 10sp → 12sp. |
| `activity_chat.xml` | **Removed** `BottomNavigationView` (Chats/News tab bar). **Fixed** `SmallSp` — bumped timestamp font from 10sp → 12sp. |
| `activity_news_feed.xml` | **Removed** `BottomNavigationView` (Chats/News tab bar). Made transport selector `android:visibility="visible"` (was `"gone"`). |
| `menu/top_app_bar_menu.xml` | **NEW** — Overflow menu with Chats, News Feed, Transport Mode submenu (SOSBlue Mesh / F2P Serverless / SMS), Settings, About |
| `menu/bottom_nav_menu.xml` | **DELETED** — Replaced by `top_app_bar_menu.xml` as part of navigation redesign |
| `AndroidManifest.xml` | Added `windowSoftInputMode="adjustResize"` to `MainActivity`. **Removed** duplicated `ACCESS_WIFI_STATE` and `CHANGE_WIFI_STATE` permission entries (were declared twice — in "Network permissions" block and "Wi-Fi Direct/P2P" block). |
| `MainActivity.java` | **Overflow menu:** Replaced inline `PopupMenu` with `R.menu.top_app_bar_menu` inflation + transport mode submenu handling with persistence. **Bottom nav:** Removed `BottomNavigationView` listener code. **Lifecycle:** Added `onStop()` + try-catch in `onDestroy()` for safe bridge cleanup. **Crash fix:** Added missing `rb_sms_fallback` case in `radioIdToTransportMode()` (was silently mapping SMS to Mesh). **Crash fix:** Added `isFinishing()`/`isDestroyed()` guard + `bridge != null` check in `onTransportModeChanged()`. **Added** `import android.util.Log`. |
| `ChatActivity.java` | **Overflow menu:** Replaced inline `PopupMenu` with `R.menu.top_app_bar_menu` inflation + transport mode submenu. **Bottom nav:** Removed `BottomNavigationView` listener code. **Dead code:** Removed `showTransportSettingsDialog()`. **Lifecycle:** Added `onStop()` + try-catch `onDestroy()` for bridge + SMS transport cleanup. **Crash fix:** Added `volatile isActivityDestroyed` flag + guards on all `runOnUiThread()` callbacks to prevent detached-activity crashes. **Crash fix:** Added root view null check before `Snackbar.make()` in error handler. **Crash fix:** Wired `pendingSmsTransport.shutdown()` in `onDestroy()`. **Fixed** `DefaultLocale` — added `Locale.ROOT` to 3 `toLowerCase()` calls. |
| `NewsFeedActivity.java` | **Overflow menu:** Replaced with `R.menu.top_app_bar_menu` inflation + transport mode submenu. **Bottom nav:** Removed `BottomNavigationView` listener code and import. **Transport selector:** Made `newsTransportScroll` visible; added `OnCheckedChangeListener` to persist mode changes. **Dead code:** Removed `showSettingsDialog()`. **Lifecycle:** Added `onStop()` + try-catch in `onDestroy()`. **Fixed** `DefaultLocale` — added `Locale.ROOT` to 3 `toLowerCase()` calls. |
| `F2PNewsPacket.java` | **Fixed** `DefaultLocale` — added `Locale.ROOT` to `bridgeName.toUpperCase()` call |
| `MessageModel.java` | **Fixed** `DefaultLocale` — added `Locale.ROOT` to all 3 `String.format()` calls for media size display |
| `NetworkConnectivityManager.java` | **Fixed** `DefaultLocale` — added `Locale.ROOT` to 2 `getName().toLowerCase()` calls |
| `SmsTransport.java` | **Crash fix:** Added `shutdown()` method to properly shut down the background executor (was never shut down — thread leak) |
| `WifiDirectManager.java` | **Crash fix:** Fixed broken code formatting in `handleConnectionInfo()` — trailing `if` statement was missing `if` keyword after misplaced block comment |
| `strings.xml` | Added `transport_sosblue_mesh` ("SOSBlue Mesh") and `transport_f2p_serverless` ("F2P Serverless") string resources for menu |
| `drawable/bg_white_round_bottom.xml` | **DELETED** — Unused |
| `drawable/bg_white_round_top.xml` | **DELETED** — Unused |
| `drawable/rounded_edittext.xml` | **DELETED** — Unused |
| `drawable/ic_bottom_news.xml` | **DELETED** — Unused (was only referenced by deleted `bottom_nav_menu.xml`) |
| `layout/*.xml` (9 files) | **Fixed** `SmallSp` — bumped font sizes from `10sp`/`11sp` to `12sp` |

### Session 10 (Conversation Inbox & Lint Cleanup)

| File | What Changed |
|------|-------------|
| `inbox/ConversationModel.java` | **NEW** — Data class with fields: `displayName`, `phone`, `lastMessage`, `timestamp`, `unreadCount`, `hasMedia`, `avatarChar`. Methods: `getRelativeTime()` (short "2m ago" / "1h ago" / "Yesterday" format), `getAvatarChar()` (first letter of display name, fallback to `#`). |
| `inbox/ConversationRegistry.java` | **NEW** — Static `ConcurrentHashMap<String, ConversationModel>` shared across activities. `update(phone, name, text, ts, outgoing, hasMedia, incrementUnread)` — atomic create-or-update with max unread cap of 99. `getAll()` returns entries sorted by newest timestamp descending. `markRead(phone)` resets unread to 0. `clearAll()` wipes the registry. |
| `inbox/ConversationAdapter.java` | **NEW** — `ListAdapter<ConversationModel>` with `DiffUtil.ItemCallback` comparing phone + preview + unread count + timestamp. `ViewHolder` inflates `item_conversation.xml` and binds: avatar circle with first letter + `@color/primary_red` background, name bold if unread > 0, last message preview (prefixed with "📷 " for media), relative timestamp, circular red unread badge with count text (hidden when 0). `setOnConversationClickListener()` callback for item tap. |
| `layout/item_conversation.xml` | **NEW** — `MaterialCardView` (dark `#1E1E1E` bg, `#2A2A2E` stroke, 12dp radius). Layout: horizontal row with `FrameLayout` avatar circle (40dp, `circle_bg` with `primary_red` tint), `LinearLayout` column (name `title_text` bold 15sp, separator, last message `peer_detail_text` 13sp, single line with ellipsis), right-side column (timestamp `peer_detail_text` 10sp, unread badge `bg_round_unseen_msg` 20dp circle with white 11sp count text, bottom-aligned). |
| `MainActivity.java` | **Major rewrite of inbox screen:** Replaced `ChatAdapter` with `ConversationAdapter`. Hides message input container (`inputContainer`) and reply preview (`replyPreviewContainer`) — these were leftover from the previous direct-send layout. Added `refreshConversationList()` method called in `onResume()` to reload from `ConversationRegistry` every time user returns. Empty state (`textStatus`) shows "No conversations yet" when registry is empty — separate from engine loading state. Conversation item click → `Intent` to `ChatActivity` with `EXTRA_RECIPIENT_PHONE` and `EXTRA_RECIPIENT_NAME` extras + calls `ConversationRegistry.markRead()`. |
| `ChatActivity.java` | **Conversation registry integration:** Added import + registration of every message event in `ConversationRegistry`: (1) `sendCurrentMessage()` registers outgoing message with resolved display name via `NotificationHelper.lookupDisplayName()`, (2) incoming mesh messages register with sender's lookup display name, (3) inbound SMS messages register with sender phone. `ConversationRegistry.markRead()` called in `onCreate()` when launched from inbox with a recipient intent extra. |
| `AndroidManifest.xml` | Added `POST_NOTIFICATIONS` (notification permission for Android 13+), `ACCESS_COARSE_LOCATION` (Wi-Fi scanning on Android 10-12), and `<uses-feature android:name="android.hardware.telephony" android:required="false" />` (silences Chrome OS lint warning for telephony-related code) |
| `SOSBlueApplication.java` | Added `@SuppressLint("NewApi")` to `isIsolatedProcess()` — removes `NewApi` lint error about `Process.isIsolated()` being API 28+ (minSdk=26) |
| `TransportMode.java` | Added `@SuppressLint("NewApi")` to `isIsolatedProcess()` + `@SuppressLint("MissingPermission")` to `getSubscriberId()` call (code already has try-catch guard) — removes `NewApi` + `MissingPermission` lint errors |
| `UserIdentity.java` | Added `@SuppressLint("NewApi")` to `isIsolatedProcess()` — removes `NewApi` lint error |
| Layout files (10 files) | Replaced all `android:tint="@color/..."` with `app:tint="@color/..."` across `activity_chat.xml`, `activity_main.xml`, `activity_news_feed.xml`, `activity_settings.xml`, `item_news_card.xml`, `item_message_media_incoming.xml`, `item_message_media_outgoing.xml` — resolved all 40 `UseAppTint` lint errors |

### Session 9 (Settings Screen, Notification Toggles, Overflow Menu Routing)

| File | What Changed |
|------|-------------|
| `settings/SettingsManager.java` | **NEW** — Lightweight SharedPreferences helper with `isChatNotificationEnabled()`, `setChatNotificationEnabled()`, `isNewsNotificationEnabled()`, `setNewsNotificationEnabled()`, and `resetToDefaults()` |
| `settings/SettingsActivity.java` | **NEW** — Dedicated settings activity with: transport mode radio group (persists via `TransportMode.save()` with SMS availability gate), notification toggle switches (`SwitchCompat` for chat + news broadcasting), about section (app icon, name, version, 3-tier transport description), and a reset-to-defaults button |
| `drawable/ic_settings.xml` | **NEW** — Vector drawable gear icon for the settings action bar |
| `layout/activity_settings.xml` | **NEW** — Scrollable dark-theme layout with `MaterialCardView` cards for each section: transport radio group, notification switches with descriptions, about card with centered icon + text |
| `NotificationHelper.java` | Added `SettingsManager` field + checks at the top of `notifyIncomingMessage()`, `notifyGroupMessage()`, and `notifyIncomingNews()` — suppresses notifications when the user has toggled them off |
| `ChatActivity.java` | Overflow menu item "Settings / Mode Switch" now launches `SettingsActivity` instead of showing an inline dialog |
| `MainActivity.java` | Same overflow menu re-routing to `SettingsActivity` |
| `NewsFeedActivity.java` | Same overflow menu re-routing to `SettingsActivity` |
| `AndroidManifest.xml` | Added `SettingsActivity` as a non-exported activity |
| `strings.xml` | Added 12+ strings for settings screen — section headers, toggle descriptions, about text, reset toast |

### Session 8 (Broadcast News Feed, Notifications, UI Polish)

| File | What Changed |
|------|-------------|
| `news/F2PNewsPacket.java` | **NEW** — News broadcast data class with `TransportType` enum (SOSBLUE_MESH / F2P_SERVERLESS / SMS_FALLBACK), SMS wire format `[NEWS:AuthorName] text`, `fromSmsText()` parser, Base64 media support |
| `news/NewsFeedActivity.java` | **NEW** — Full broadcast news feed: `BottomNavigationView` with Chats/News tabs, card-based `RecyclerView`, transport radio selector, compose bar with text + media attachment + send, search bar overlay, overflow menu, demo items |
| `news/NewsAdapter.java` | **NEW** — `ListAdapter` with DiffUtil, colored chip-style transport badges (red/green/blue-grey), relative timestamps, slide-in entrance animation, read/unread alpha |
| `notification/NotificationHelper.java` | **NEW** — Complete notification system: 2 high-priority channels (chat + news), `notifyIncomingMessage()` with `MessagingStyle` for 1:1 conversations, `notifyGroupMessage()` with `setGroupConversation(true)` for multi-sender group chats, static phone→display-name cache, static per-sender and per-group message history with `SharedPreferences` JSON persistence (load on init, save after every write, clear on `cancelAll()`), stable per-conversation notification IDs, `Person` builder for sender attribution, 50-entry history cap |
| `menu/bottom_nav_menu.xml` | **NEW** — Simple 2-tab menu (`nav_chats` + `nav_news`) |
| `drawable/ic_notification_chat.xml` | **NEW** — Vector drawable for chat notification icon |
| `drawable/ic_notification_news.xml` | **NEW** — Vector drawable for news notification icon |
| `drawable/ic_rss.xml` | **NEW** — RSS/broadcast icon for top action bar |
| `drawable/ic_bottom_chat.xml` | **NEW** — Bottom nav Chats tab icon |
| `drawable/ic_bottom_news.xml` | **NEW** — Bottom nav News tab icon |
| `activity_chat.xml` | Added search bar overlay (`@+id/searchBarOverlay` + `@+id/searchInput` + `@+id/searchCloseIcon`) with fade animation; added `BottomNavigationView` at bottom |
| `activity_news_feed.xml` | **NEW** — Full news feed layout: top bar with search + overflow, transport radio, RecyclerView, compose bar, bottom nav |
| `item_news_card.xml` | **NEW** — News card with MaterialCardView: author row, colored transport badge chip, relative timestamp, text body, media indicator |
| `ChatActivity.java` | **Top bar handlers:** search icon toggles animated search bar (real-time filter by text/sender), broadcast (feed) icon opens `NewsFeedActivity`, 3-dot overflow shows PopupMenu (Switch to Chat/News, Settings/Mode Switch, About/Help). **Bottom nav:** `BottomNavigationView` with Chats/News tabs switches between activities. **Search:** `allMessages` list tracks all messages + `searchInput` field for filtering with TextWatcher. **Notifications:** Incoming messages trigger `NotificationHelper.notifyIncomingMessage()`; mesh broadcasts (transport=`"mesh"`) route to `notifyGroupMessage()` with group ID `"sosblue_mesh_broadcast"`. **Display name cache:** Registers local user identity in cache at startup; registers `PeerDiscoveryListener` to populate cache from heartbeats. |
| `MainActivity.java` | **Top bar handlers:** search icon toggles inline search bar, broadcast icon opens `NewsFeedActivity`, 3-dot overflow shows PopupMenu (Switch to Chat/News, Settings, About). **Bottom nav:** `BottomNavigationView` with Chats/News tabs. **Dialogs:** `showTransportSettingsDialog()` + `showAboutDialog()` added. |
| `colors.xml` | Added `f2p_badge_bg` (#388E3C), `sms_badge_bg` (#455A64) for news card transport chip backgrounds |
| `strings.xml` | Added 20+ new strings for news broadcast, bottom navigation, notifications, overflow menu, dialogs, and search |
| `AndroidManifest.xml` | Added `NewsFeedActivity` activity declaration |
| `HANDOVER.md` | Updated with all Session 8 changes — new files, modified files, feature list, architecture, outstanding tasks |

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

### ✅ Media Download — Save Received Images/Videos to Gallery (Session 21)
- **Download button on incoming media** — A download icon (downward arrow in a semi-transparent circle) appears at the top-right corner of received image/video bubbles, visible only on incoming (not outgoing) media.
- **Confirmation dialog popup** — Tapping the download icon shows an `AlertDialog` with file name, size (e.g. "2.4 MB"), and MIME type (e.g. "image/jpeg"). User confirms with "Save to Gallery".
- **MediaStore save (Android 10+)** — Images saved to `PICTURES/SOSBlue/`, videos to `MOVIES/SOSBlue/` using `MediaStore` API with `IS_PENDING` flag for atomic writes. No storage permission needed.
- **Direct file save (Android 9 and below)** — Writes to external storage public directory with `WRITE_EXTERNAL_STORAGE` permission, followed by `ACTION_MEDIA_SCANNER_SCAN_FILE` broadcast for immediate gallery visibility.
- **Background thread I/O** — File reading and writing runs on a background thread to prevent ANR on large video files (up to 100MB cap from MediaChunker).
- **`isActivityDestroyed` guards** — All `runOnUiThread()` callbacks in download methods check `isActivityDestroyed` before showing Toasts, preventing detached-activity crashes.
- **Runtime permission (pre-Android 10)** — `WRITE_EXTERNAL_STORAGE` requested via dedicated `storagePermissionLauncher`, with `pendingDownloadMessage` field to retry save after grant. Permission declared with `android:maxSdkVersion="28"`.

### ✅ Media Chunk Integrity & Bluetooth/Wi-Fi Permissions (Session 19-20)
- **Media chunk checksum fix** — `MediaChunker.computeChecksum()` now exposes the internal SHA-256 hasher so received chunks are validated with a real checksum instead of an empty byte array. Fixes broken image/video reconstruction over F2P.
- **Bluetooth + Wi-Fi runtime permissions** — When the user selects SOSBlue Mesh mode, the app now requests `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` (API 31+) or legacy `BLUETOOTH` + `BLUETOOTH_ADMIN` (pre-API 31). This ensures the peer discovery mechanisms work without silent failures.
- **Re-entrant mode switch guard** — `onTransportModeChanged()` now uses `isModeSwitching` flag + `currentTransportMode` field to prevent nested or redundant mode transitions that could crash the activity.
- **Engine stop on mode leave** — When switching away from F2P Serverless mode, the F2P engine is now properly stopped and `engineReady` reset — fixes a resource leak where the engine kept running in the background.
- **Buffering spinner for all transitions** — Every mode switch now shows a loading progress spinner until the transition completes, with a Snackbar error fallback if the transition fails.
- **Null-safe guards across the board** — Bridge, peer bar adapter, peer count badge, and buffering view are all null-checked before use; `sendCurrentMessage()` and `onMediaSelected()` show Snackbar errors instead of crashing when bridge is null.

### ✅ Transport Badge on Inbox Conversation Cards (Session 18)
- **Colored transport chip on each conversation card** — A small pill badge (18dp tall, 9sp text) now appears between the display name and timestamp on every conversation card in the inbox, showing which transport tier was used for the last message.
- **Color-coded by transport:**
  - **Blue** (`#2196F3`) — "MESH" for SOSBlue Mesh conversations
  - **Green** (`#388E3C`) — "F2P" for F2P Serverless conversations
  - **Blue-grey** (`#455A64`) — "SMS" for SMS Relay conversations
- **Hidden when unknown** — Badge only appears when a transport mode has been recorded, falling back gracefully for legacy conversations.
- **Programmatic pill styling** — Uses `GradientDrawable` with rounded corners for a clean, chip-like appearance without needing a separate drawable resource.

### ✅ Menu Simplified to Settings + How SOSBlue Works (Session 17)
- **Replaced "News Feed" menu item** with "How SOSBlue Works" — the overflow menu now shows only Settings and the new About option.
- **Comprehensive how-it-works dialog** — tapping "How SOSBlue Works" shows a detailed AlertDialog explaining: identity/E2E encryption, all 3 transport tiers (Mesh, F2P Serverless, SMS Relay), peer discovery (UDP heartbeats), news broadcasts, and the offline-first philosophy.
- **News Feed still accessible** via the RSS/feed icon in the top bar of all activities.

### ✅ SMS Broadcast Targets SMS Contacts Only (Session 16)
- **Conversation transport tracking** — `ConversationModel` now stores `lastTransportMode` ("SOSBLUE_MESH", "F2P_SERVERLESS", or "SMS_FALLBACK") for each conversation. `ConversationRegistry.update()` accepts and persists the transport mode.
- **Targeted SMS news broadcast** — News broadcasts sent via SMS are now delivered only to contacts previously chatted with via SMS (filtered by `lastTransportMode == "SMS_FALLBACK"`). Shows a helpful message if no SMS contacts exist yet.
- **Sent/fail tracking** — For multi-recipient SMS broadcasts, tracks success/failure counts and shows a summary toast.

### ✅ Crash Audit & Snackbar NPE Protection (Session 15)
- **ChatActivity crash shield** — Entire `onCreate` body extracted into `initializedOnCreate()` wrapped in a try-catch. Any startup exception is logged, shown to the user as a Toast, and the activity finishes gracefully instead of silently crashing to home screen.
- **6 async Snackbar NPE paths guarded** — All `Snackbar.make(findViewById(R.id.root), ...)` calls in async callbacks (Wi-Fi permission result, SMS permission result, `showSmsError()`, F2P engine error, send failure, media send failure) now check `isActivityDestroyed` and null-check the root view before showing. Prevents crashes when permission dialogs or bridge callbacks fire after the activity is destroyed.
- **ExtendedFloatingActionButton** — FAB now shows "Start New Chat" text + compose icon, making its purpose immediately clear to users.
- **FAB click safety** — try-catch wrapper around `startActivity()` in click handler.

### ✅ New Chat FAB — Start Conversations from Home (Session 14)
- **FloatingActionButton** (56dp red circle with compose icon) pinned to bottom-right of the inbox screen.
- Tapping the FAB opens `ChatActivity` with an empty recipient field — type any phone number to start a new 1-on-1 conversation.
- RecyclerView has 80dp bottom padding so the last conversation card is always visible above the FAB.
- Empty state now reads "Tap + to start a new chat" giving clear next-step guidance.

### ✅ Clean Conversation Inbox — Home Screen UX Refactored (Session 13)
- **`MainActivity` completely stripped down** to a pure conversation inbox. Removed ALL: message composers, send buttons, transport radio group (SOSBlue Mesh / F2P Serverless / SMS), peer discovery panel, reply preview, and loading indicators.
- The home screen now shows just: a simplified top bar (app icon + title + search + RSS feed icon + overflow menu), a `RecyclerView` of recent conversations with avatars, and an empty state when no chats exist.
- Search icon toggles an inline search bar that filters conversations in real-time by display name or message text.
- Tapping a conversation card navigates to `ChatActivity` with the recipient's phone and name pre-filled.

### ✅ Transport Controls Unified Inside ChatActivity (Session 13)
- **All transport controls live exclusively in `ChatActivity`**: the radio button group (SOSBlue Mesh | F2P Serverless | SMS), E2E encryption badge, recipient phone input, message composer, attach button, and send button are all at the bottom of the conversation screen.
- Transport mode persists across sessions via `TransportMode.save()/load()` from SharedPreferences.
- The engine lifecycle (F2PBridge start/stop, SMS transport init) is fully managed by ChatActivity — MainActivity has zero bridge initialization code.

### ✅ Streamlined Overflow Menu (Session 13)
- **`top_app_bar_menu.xml`** simplified from 6 items to just 2: "News Feed" and "Settings".
- Removed: "Chats" (redundant — the home screen IS the Chats view), Transport Mode submenu (now handled by inline RadioGroup in ChatActivity), and "About" (moved to Settings screen).
- The RSS/feed icon in the top bar remains as a quick-access button to NewsFeedActivity.

### ✅ Conversation Card Polish (Session 13)
- Larger avatar circles (46dp) with bold white initial letters on a red background.
- Improved typography: display name 16sp bold, timestamp 11sp muted, last message preview 13sp.
- Unread badge styled as a red pill with `primary_red` background tint.
- Subtle `#2A2A2E` card stroke acts as a visual divider between conversations without extra spacing.

### ✅ Launcher Activity Fix — App No Longer Opens Wrong Screen (Session 12)
- **Moved MAIN/LAUNCHER intent filter** from `ChatActivity` (1-on-1 chat) to `MainActivity` (conversation inbox). Previously, tapping the app icon opened a raw 1-on-1 chat screen with no context — now it opens the proper inbox with the conversation list.
- **Updated `SignInActivity.launchChat()`** to launch `MainActivity` after onboarding instead of `ChatActivity`.
- **Fixed all overflow menus** so "Chats" consistently opens the conversation inbox (`MainActivity`), not a 1-on-1 chat screen.

### ✅ Android 14+ BroadcastReceiver Crash Fix (Session 12)
- **Added `Context.RECEIVER_EXPORTED` flag** to all 3 `registerReceiver()` calls in `WifiDirectManager` and `NetworkConnectivityManager`. This flag is **mandatory on Android 14+** — without it, the system throws a `SecurityException` that instantly crashes the app to the home screen on API 34+ devices.
- Correctly gated with `Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU` for backward compatibility with API < 33 where the flag doesn't exist.

### ✅ POST_NOTIFICATIONS Runtime Permission (Session 12)
- Added `notificationPermissionLauncher` and `requestNotificationPermissionIfNeeded()` to both `MainActivity` and `ChatActivity`.
- The permission dialog now appears on Android 13+ when the app starts, as required by the OS for posting notifications.

### ✅ Global Crash Handler — Stops Silent Exits (Session 12)
- Added `Thread.setDefaultUncaughtExceptionHandler()` in `SOSBlueApplication.onCreate()` that logs the crash with device model + SDK version, then delegates to the default handler.
- Ensures all crashes are visible in logcat with diagnostic context instead of silently killing the app.

### ✅ Navigation Redesign (Session 11)
- **Removed floating bottom tab bar** from all 3 activities (Chats/News tabs) — navigation moved entirely to the top bar's 3-dot overflow menu and RSS/feed icon
- **Created `top_app_bar_menu.xml`** with proper menu structure: Chats, News Feed, Transport Mode submenu (SOSBlue Mesh / F2P Serverless / SMS Relay), Settings, About
- **Transport mode in overflow menu** — users can switch between all 3 tiers directly from the overflow submenu with visual checkmark
- **RSS/feed icon** in each top bar opens NewsFeedActivity — retained as a quick-access button
- **Keyboard overlap fix** — `windowSoftInputMode="adjustResize"` applied to `MainActivity` (ChatActivity already had it); weighted RecyclerView layout ensures messages compress above the input bar

### Lifecycle Stability (Session 11)
- `onStop()` + try-catch `onDestroy()` cleanup added to all 3 activities — bridge engine stop, SMS transport unregister, and notification helper cleanup are all safely wrapped
- Transport initialization safe from NPEs via try-catch blocks

### ✅ Crash Audit & Stability Fixes (Session 11)
- **MainActivity SMS mode bug:** Fixed `radioIdToTransportMode()` — added missing `SMS_FALLBACK` case that was silently mapping SMS selection to Mesh, making SMS transport unusable from overflow menu
- **MainActivity NPE guard:** Added `isFinishing()`/`isDestroyed()` guard + `bridge != null` check + root view null check in `onTransportModeChanged()`
- **ChatActivity detached-activity crash:** Added `volatile isActivityDestroyed` flag set in `onDestroy()`, with guards on all `runOnUiThread()` callbacks that access UI elements (chatAdapter, Snackbar)
- **ChatActivity Snackbar NPE:** Added root view null check before `Snackbar.make()` in the packet error handler
- **SmsTransport executor leak:** Added `shutdown()` method, wired into `ChatActivity.onDestroy()`; executor was previously never shut down, causing thread leak on every Activity recreation
- **WifiDirectManager broken code:** Fixed missing `if` keyword in `handleConnectionInfo()` — trailing block comment left the if-statement syntactically broken
- **Lifecycle cleanup:** `onStop()` + try-catch `onDestroy()` with safe bridge stop, SMS transport unregister/shutdown, and notification helper cleanup in all 3 activities

### ✅ Lint Cleanup (Session 11)
- **Reduced lint warnings from 221 → 183 (17% reduction), 0 errors**
- **Fixed DefaultLocale (11 in app files):** Added `Locale.ROOT` to all `toLowerCase()`/`toUpperCase()`/`String.format()` calls across `ChatActivity`, `F2PNewsPacket`, `MessageModel`, `NetworkConnectivityManager`, `NewsFeedActivity`
- **Fixed InefficientWeight (2):** Changed `layout_width="wrap_content"` → `"0dp"` for weighted layouts in `activity_main.xml`
- **Fixed NestedWeights (1):** Eliminated nested weight in `activity_main.xml` by replacing inner layout's weight with `wrap_content` + `layout_gravity="end"`
- **Fixed SmallSp (5 → 0 in app):** Increased font sizes from 10sp/11sp to 12sp across 9 layout files
- **Removed unused resources:** Deleted 4 unused drawables (`bg_white_round_bottom.xml`, `bg_white_round_top.xml`, `rounded_edittext.xml`, `ic_bottom_news.xml`) + `bottom_nav_menu.xml`
- **Fixed duplicate manifest permissions:** Removed duplicated `ACCESS_WIFI_STATE` and `CHANGE_WIFI_STATE` declarations

### ✅ Conversation Inbox
- `MainActivity` now shows a conversation list (inbox) instead of a direct message composer — users see all active chats with latest message preview, sender name, timestamp, and unread badge
- `ConversationRegistry` (static `ConcurrentHashMap`) shared between `ChatActivity` and `MainActivity` — automatically updated on every sent/received/SMS message
- `ConversationAdapter` with `DiffUtil` ensures smooth list updates with correct item animations
- Conversation cards show: avatar circle with first letter (red bg), display name (bold when unread), media indicator (📷), relative timestamp ("2m ago", "Yesterday"), unread count badge (red circle)
- Tapping a conversation opens `ChatActivity` with recipient pre-filled; unread count resets to 0
- Inbox refreshes on `onResume()` — always shows latest state when user returns

### ✅ Settings Screen
- `SettingsActivity` provides a dedicated settings screen with three card-based sections: Transport Mode, Notifications, and About
- Transport mode selector uses a `RadioGroup` persisting via `TransportMode.save()` with SMS availability gate and toast feedback
- Notification toggles (chat + news) backed by `SettingsManager` using `SharedPreferences` — changes reflected immediately
- About section displays app icon (red circle), name, version, and 3-tier transport description
- Overflow menus in `ChatActivity`, `MainActivity`, and `NewsFeedActivity` all route to `SettingsActivity`
- `NotificationHelper` checks `SettingsManager` before posting every notification — suppresses when toggled off
- Reset-to-defaults button restores all toggles to `true` and transport to SOSBlue Mesh

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
| **Low** | Clean up remaining unused resources | ~42 unused colors/strings flagged by lint in `strings.xml`, `colors.xml` — leftover from earlier iterations |
| **Low** | Fix remaining HardcodedText warnings | 72 hardcoded strings across layout files — move to `strings.xml` |
| **Low** | Fix ContentDescription warnings | 24 ImageViews missing content descriptions — accessibility |
| **Low** | Fix Autofill warnings | 9 layout fields missing autofill hints |
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
| **Navigation redesign — bottom nav removed** | Removed `BottomNavigationView` from all 3 layouts; navigation moved to top bar overflow menu + RSS icon |
| **Top bar overflow menu with transport submenu** | Created `top_app_bar_menu.xml` with Chats, News Feed, Transport Mode submenu (3 tiers), Settings, About — used by all 3 activities |
| **News feed transport selector visible** | Made transport radio group visible in `NewsFeedActivity` with OnCheckedChangeListener for persistence |
| **Lifecycle crash fixes** | Added `onStop()` + try-catch `onDestroy()` cleanup to MainActivity, ChatActivity, NewsFeedActivity — safe bridge/SMS/notification teardown |
| **Dead code removed** | `showTransportSettingsDialog()` from ChatActivity, `showSettingsDialog()` from NewsFeedActivity |
| **DefaultLocale lint warnings fixed** | Added `Locale.ROOT` to 11 `toLowerCase()`/`toUpperCase()`/`String.format()` calls across 5 app files |
| **InefficientWeight / NestedWeights fixed** | Layout width fixes in `activity_main.xml` — replaced nested weight with `wrap_content` + `layout_gravity="end"` |
| **SmallSp font sizes fixed** | Bumped from 10sp/11sp → 12sp across 9 layout files |
| **Duplicate manifest permissions removed** | Removed duplicated `ACCESS_WIFI_STATE` and `CHANGE_WIFI_STATE` declarations |
| **Unused resources deleted** | Removed 4 unused drawables + `bottom_nav_menu.xml` |
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
