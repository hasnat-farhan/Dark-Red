# Wandering Fibre Engine

A serverless mesh-networking engine designed for the SOSBlue Android application. It provides dynamic peer-to-peer routing, end-to-end encryption, offline packet persistence, and a production-grade lifecycle — all without blocking the main application thread.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [State Machine & Mesh Lifecycle](#state-machine--mesh-lifecycle)
3. [Package Structure](#package-structure)
4. [API Reference](#api-reference)
5. [Configuration](#configuration)
6. [Quickstart](#quickstart)
7. [Build & Test](#build--test)
8. [Git & Repository Notes](#git--repository-notes)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                        SOSBlue (Android)                    │
│  ┌─────────────────────────────────────────────────────────┐│
│  │                    F2PBridge                             ││
│  │  (only class that imports from com.antor.f2p.engine.*)   ││
│  └──────────────┬──────────────────────────────────────────┘│
└─────────────────┼───────────────────────────────────────────┘
                  │  imports from api/ only
                  ▼
┌─────────────────────────────────────────────────────────────┐
│              Wandering Fibre Engine                          │
│  ┌──────────┐  ┌──────────┐  ┌────────────┐  ┌───────────┐ │
│  │   api/   │  │  core/   │  │  network/  │  │   test/   │ │
│  │ (public) │  │(internal)│  │(internal)  │  │(verif.)   │ │
│  └──────────┘  └──────────┘  └────────────┘  └───────────┘ │
└─────────────────────────────────────────────────────────────┘
```

**Isolation rule:** SOSBlue imports exclusively from `com.antor.f2p.engine.api.*`. The `core/` and `network/` packages are internal and invisible to the application.

---

## State Machine & Mesh Lifecycle

```
                    ┌──────────────┐
                    │ UNINITIALIZED │
                    └──────┬───────┘
                           │ initialize()
                           ▼
                    ┌──────────────────┐
              ┌─────│ DISCOVERING_PEERS │◄────────┐
              │     └────────┬─────────┘         │
              │              │ peers found       │ reconnect()
              │              ▼                   │
              │     ┌───────────────┐            │
              ├────►│ CONNECTED_MESH ├──►────────┤
              │     └───────┬───────┘            │
              │             │ startRouting()      │
              │             ▼                    │
              │     ┌──────────┐                 │
              │     │  ROUTING ├──►──────────────┤
              │     └────┬─────┘                 │
              │          │ fail                  │
              │          ▼                       │
              │     ┌────────┐                   │
              └─────│  ERROR ├──────────────────►│
              │     └───┬────┘                   │
              └─────────┤ reset()                │
                        │                        │
                        └────────────────────────┘
```

| State | Description |
|-------|-------------|
| `UNINITIALIZED` | Engine created but not started |
| `DISCOVERING_PEERS` | Heartbeat broadcast: searching for nearby mesh nodes |
| `CONNECTED_MESH` | ≥ 1 peer discovered; mesh formed but not routing |
| `ROUTING` | Actively routing packets over the mesh |
| `DISCONNECTED` | All peers lost; auto-retries via DISCOVERING_PEERS |
| `ERROR` | Fatal error; requires reset to UNINITIALIZED |

---

## Package Structure

```
f2p-serverless/wandering-fibre-engine/
├── api/                          ← Public API (SOSBlue's only import target)
│   ├── EngineCallback.java       — Callback interface (signal, packet, state, error, diagnostics)
│   ├── EngineConfig.java         — Configuration POJO with builder
│   ├── EngineState.java          — Mesh lifecycle states enum
│   ├── FibrePacket.java          — Low-level mesh packet (node IDs, seq#, payload)
│   ├── FibreSignal.java          — High-level application signal
│   ├── LogLevel.java             — Log levels (TRACE…ERROR) for runtime toggling
│   ├── MeshHealthSnapshot.java   — Immutable telemetry snapshot
│   └── WanderingFibreEngine.java — Main entry point
│
├── core/                         ← Internal — state machine, loop, crypto, persistence
│   ├── EngineLoop.java           — Adaptive async execution loop (daemon thread)
│   ├── FibreEngineStateMachine.java — Event-driven state machine with triggers
│   ├── FibreLogger.java          — Non-blocking async logger (daemon thread + file I/O)
│   ├── FibreProcessor.java       — Signal→packet conversion, routing, encrypt/decrypt
│   ├── FibreSecurityHandler.java — AES-256-GCM + ECDH handshake
│   ├── FibreStore.java           — File-based offline persistence
│   ├── PacketBufferPool.java     — [@deprecated] Ring-buffer pool reserved for future use
│   └── StateMachine.java         — Pure transition validation matrix
│
├── network/                      ← Internal — peer discovery, routing, path computation
│   ├── FibrePathRouter.java      — Dijkstra shortest-path routing with ACK/NACK
│   ├── LinkMetrics.java          — Link quality (latency, signal, hops, loss)
│   ├── PeerDiscovery.java        — Peer registry with TTL eviction
│   ├── PeerDiscoveryHandler.java — Mock heartbeat-based discovery
│   └── RoutingTable.java         — Static + dynamic route resolution
│
├── test/                         ← Verification & integration
│   ├── IntegrationTest.java      — 7 end-to-end lifecycle + persistence tests
│   ├── MeshRoutingSimulationTest.java — 5 routing + ACK/NACK + Dijkstra tests
│   ├── SecurityTest.java         — 5 encryption round-trip + handshake tests
│   └── ValidationRunner.java     — Full engine lifecycle smoke test
│
├── README.md                     ← This file
├── .gitignore
└── logs/                         ← Created at runtime by FibreLogger
│
└── store/                        ← Created at runtime by FibreStore (peer cache, queued packets)
```

---

## API Reference

### `WanderingFibreEngine`

| Method | Description |
|--------|-------------|
| `initialize()` | Starts the engine: crypto init → peer discovery → engine loop → routing |
| `shutdown()` | Graceful stop: flushes store, persists caches, stops all threads |
| `configure(EngineConfig)` | Applies configuration before `initialize()` |
| `dispatchSignal(FibreSignal)` | Enqueue a signal for async processing |
| `dispatchSignal(String, Map)` | Convenience: creates + enqueues a signal |
| `pauseRouting()` | Pause processing; signals are queued to FibreStore |
| `resumeRouting()` | Resume processing; queued packets are flushed |
| `getState()` | Current `EngineState` |
| `isRouting()` / `isConnected()` / `isPaused()` | Boolean state checks |
| `registerListener(EngineCallback)` / `unregisterListener(...)` | Add/remove callbacks |
| `getHealthSnapshot()` | Immutable `MeshHealthSnapshot` with telemetry |
| `getNetworkDiagnostics()` | Human-readable diagnostics string |
| `setLocalNodeId(String)` / `getLocalNodeId()` | Node identity |

### `EngineCallback` (all methods have default no-op implementations)

| Method | When called |
|--------|-------------|
| `onSignal(FibreSignal)` | A signal completes processing through the engine |
| `onPacketReceived(FibrePacket)` | A packet arrives (local or from mesh) |
| `onStateChanged(String)` | Engine state transition (string form) |
| `onStateChanged(EngineState, EngineState)` | Engine state transition (typed form) |
| `onError(Throwable)` | Non-fatal engine error |
| `onEngineError(int, String, Throwable)` | Error-boundary callback with status code |
| `onDiagnostics(String)` | Diagnostic snapshot for monitoring |

### `F2PBridge` (SOSBlue-facing)

| Method | Description |
|--------|-------------|
| `startEngine(EngineConfig)` | Configure + initialize engine (safe to call multiple times) |
| `stopEngine()` | Graceful shutdown |
| `pauseRouting()` / `resumeRouting()` | Flow control |
| `getNetworkDiagnostics()` | Returns diagnostics string |
| `getMeshHealth()` | Returns `MeshHealthSnapshot` |
| `getActiveNodeCount()` / `getActiveRouteCount()` | Quick telemetry queries |
| `getPendingAckCount()` / `getActiveSessionCount()` | ACK + crypto session stats |
| `getQueuedOfflineCount()` | Packets waiting in offline store |
| `setLogLevel(LogLevel)` / `getLogLevel()` | Runtime logger level control |
| `getLastStatusCode()` / `getLastStatusMessage()` / `clearStatus()` | Error-boundary status |

### Error-Boundary Status Codes

| Code | Meaning |
|------|---------|
| `0` | Healthy |
| `1` | Engine initialisation failed |
| `2` | Engine shutdown error |
| `100` | Crypto initialisation failed |
| `201` | `onSignal` listener threw |
| `202` | `onPacketReceived` listener threw |
| `203` | `onStateChanged` listener threw |

---

## Configuration

All configuration is via `EngineConfig.builder()`:

```java
EngineConfig config = EngineConfig.builder()
    .nodeId("my-android-device")
    .logLevel(LogLevel.DEBUG)
    .heartbeatIntervalMs(500)       // peer discovery heartbeat (ms)
    .peersRequired(3)                // min peers to form mesh
    .maxHeartbeatCycles(5)           // max discovery cycles before timeout
    .linkTtlMs(10_000)               // link TTL (ms)
    .maxRetryAttempts(5)             // ACK retries before timeout
    .baseBackoffMs(200)              // initial backoff (doubles each retry)
    .build();
```

| Field | Default | Description |
|-------|---------|-------------|
| `nodeId` | `"sosblue-node"` | Unique identifier in the mesh |
| `logLevel` | `INFO` | Minimum log level at startup; changeable via `setLogLevel()` |
| `heartbeatIntervalMs` | `500` | Interval between peer-discovery heartbeats |
| `peersRequired` | `3` | Minimum peers to consider mesh connected |
| `maxHeartbeatCycles` | `5` | Max discovery cycles before declaring failure |
| `linkTtlMs` | `10_000` | Time-to-live for a peer link (ms) |
| `maxRetryAttempts` | `5` | Max ACK retry attempts before timeout fires |
| `baseBackoffMs` | `200` | Base exponential-backoff delay (doubles each attempt) |

---

## Quickstart

### Running the validation smoke test

```bash
# From the project root (/media/kn8/D_Drive/Projects/Dark-Red)

# 1. Compile everything
javac -d /tmp/wfe-out $(find f2p-serverless/wandering-fibre-engine -name '*.java')

# 2. Run the full lifecycle smoke test
java -cp /tmp/wfe-out com.antor.f2p.engine.test.ValidationRunner

# 3. Run the integration test (7 tests — lifecycle, persistence, diagnostics, error boundary)
java -cp /tmp/wfe-out com.antor.f2p.engine.test.IntegrationTest

# 4. Run all tests
java -cp /tmp/wfe-out com.antor.f2p.engine.test.SecurityTest
java -cp /tmp/wfe-out com.antor.f2p.engine.test.MeshRoutingSimulationTest
```

### Starting the engine from SOSBlue

```java
// In your Activity or Application class:
F2PBridge bridge = new F2PBridge();

EngineConfig config = EngineConfig.builder()
    .nodeId(BuildConfig.APPLICATION_ID + "-" + System.currentTimeMillis())
    .logLevel(LogLevel.INFO)
    .build();

bridge.startEngine(config);
bridge.registerListener(new EngineCallback() {
    @Override public void onSignal(FibreSignal s) { /* handle signal */ }
    @Override public void onStateChanged(String s) { Log.d("Mesh", "State: " + s); }
    @Override public void onEngineError(int code, String msg, Throwable t) { /* handle */ }
});

bridge.dispatchSignal("app_ready", Map.of("version", BuildConfig.VERSION_NAME));

// On pause/resume:
bridge.pauseRouting();
// ... later ...
bridge.resumeRouting();

// On app destroy:
bridge.stopEngine();
```

---

## Build & Test

### Prerequisites

- Java 11+ (`javac` + `java`)
- Android SDK (for SOSBlue integration)
- Gradle 8.x (wrapped in `SOSBlue/gradlew`)

### Compile verification

```bash
# Compile engine sources only
cd /media/kn8/D_Drive/Projects/Dark-Red
javac -d /tmp/wfe-out $(find f2p-serverless/wandering-fibre-engine -name '*.java')
echo "Exit: $?"   # Should print 0

# Compile with Android build system
cd SOSBlue && ./gradlew assembleDebug
```

### Test results

All 27 tests pass:

| Suite | Tests | Status |
|-------|-------|--------|
| `IntegrationTest` | 7 | ✅ All passed |
| `SecurityTest` | 5 | ✅ All passed |
| `MeshRoutingSimulationTest` | 5 | ✅ All passed |
| `ValidationRunner` | 1 (lifecycle) | ✅ PASSED |

---

## Git & Repository Notes

- **No nested `.git` folders**: The entire project uses a single Git root at `/media/kn8/D_Drive/Projects/Dark-Red/.git/`.
- **Gitignore coverage**: `f2p-serverless/.gitignore` excludes `*.class`, `build/`, `out/`, `logs/`, `store/`, IDE files, and OS metadata.
- **No SNAPSHOT dependencies**: All code is plain Java/Android with no external runtime dependencies beyond the JDK and AndroidX.

---

## Threading Model

| Thread | Role | Type |
|--------|------|------|
| `wandering-fibre-engine-loop` | Adaptive engine loop (signal/packet processing) | Daemon |
| `peer-discovery-heartbeat` | Mock peer discovery heartbeat | Daemon |
| `fibre-logger-writer` | Async log writer (stdout + file) | Daemon |

All engine threads are daemon threads — they never prevent JVM/process shutdown.
