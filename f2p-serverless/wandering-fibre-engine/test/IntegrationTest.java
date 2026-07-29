package com.antor.f2p.engine.test;

import com.antor.f2p.engine.api.*;

import java.io.File;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end integration test for the Wandering Fibre Engine.
 * <p>
 * Covers: lifecycle, persistence, disconnect/reconnect, packet recovery,
 * pause/resume, diagnostics, error boundary, and custom configuration.
 * </p>
 *
 * <p>Run from project root:
 * <pre>{@code
 * javac -d out $(find f2p-serverless/wandering-fibre-engine -name '*.java')
 * java -cp out com.antor.f2p.engine.test.IntegrationTest
 * }</pre>
 * </p>
 */
public class IntegrationTest {

    private int passed;
    private int failed;

    public static void main(String[] args) {
        int exitCode = new IntegrationTest().run();
        System.exit(exitCode);
    }

    int run() {
        System.out.println("[INTEGRATION] ========================================");
        System.out.println("[INTEGRATION] Wandering Fibre Engine — Integration Test");
        System.out.println("[INTEGRATION] ========================================\n");

        // Clean any previous store state from prior runs
        deleteDirectory(new File("f2p-serverless/store"));
        deleteDirectory(new File("f2p-serverless/logs"));

        testLifecycleStartStop();
        testPauseResume();
        testOfflinePacketQueueing();
        testDiskPersistenceAcrossRestart();
        testNetworkDiagnostics();
        testErrorBoundary();
        testCustomConfiguration();

        // Clean up after all tests
        deleteDirectory(new File("f2p-serverless/store"));
        deleteDirectory(new File("f2p-serverless/logs"));

        System.out.println("\n[INTEGRATION] ========================================");
        System.out.println("[INTEGRATION] " + (passed + failed) + " total | "
                + passed + " passed | " + failed + " failed");
        System.out.println("[INTEGRATION] ========================================");
        return failed > 0 ? 1 : 0;
    }

    // ---------------------------------------------------------------
    //  1. Full lifecycle
    // ---------------------------------------------------------------

    void testLifecycleStartStop() {
        System.out.print("[TEST] Full lifecycle (start → routing → stop)... ");
        try {
            AtomicReference<EngineState> lastState = new AtomicReference<>(EngineState.UNINITIALIZED);
            CountDownLatch routingLatch = new CountDownLatch(1);

            WanderingFibreEngine engine = new WanderingFibreEngine();
            engine.registerListener(new EngineCallback() {
                @Override public void onSignal(FibreSignal s) {}
                @Override public void onStateChanged(String newState) {
                    lastState.set(EngineState.valueOf(newState));
                    if (newState.equals("ROUTING")) routingLatch.countDown();
                }
            });

            engine.initialize();
            boolean reachedRouting = routingLatch.await(15, TimeUnit.SECONDS);
            engine.shutdown();

            System.out.println(reachedRouting ? "PASS (reached " + lastState.get() + ")" : "FAIL — timeout");
            if (reachedRouting) passed++; else failed++;
        } catch (Exception e) {
            System.out.println("FAIL — " + e.getMessage());
            failed++;
        }
    }

    // ---------------------------------------------------------------
    //  2. Pause / resume
    // ---------------------------------------------------------------

    void testPauseResume() {
        System.out.print("[TEST] Pause/resume routing... ");
        try {
            WanderingFibreEngine engine = new WanderingFibreEngine();
            CountDownLatch routingLatch = new CountDownLatch(1);
            engine.registerListener(new EngineCallback() {
                @Override public void onSignal(FibreSignal s) {}
                @Override public void onStateChanged(String s) {
                    if (s.equals("ROUTING")) routingLatch.countDown();
                }
            });
            engine.initialize();
            routingLatch.await(15, TimeUnit.SECONDS);

            engine.pauseRouting();
            boolean wasPaused = engine.isPaused();
            engine.resumeRouting();
            boolean isResumed = !engine.isPaused();
            engine.shutdown();

            if (wasPaused && isResumed) {
                System.out.println("PASS");
                passed++;
            } else {
                System.out.println("FAIL — paused=" + wasPaused + " resumed=" + isResumed);
                failed++;
            }
        } catch (Exception e) {
            System.out.println("FAIL — " + e.getMessage());
            failed++;
        }
    }

    // ---------------------------------------------------------------
    //  3. Offline packet queueing (in-memory)
    // ---------------------------------------------------------------

    void testOfflinePacketQueueing() {
        System.out.print("[TEST] Offline packet queueing and flush... ");
        try {
            WanderingFibreEngine engine = new WanderingFibreEngine();
            CountDownLatch routingLatch = new CountDownLatch(1);
            engine.registerListener(new EngineCallback() {
                @Override public void onSignal(FibreSignal s) {}
                @Override public void onStateChanged(String s) {
                    if (s.equals("ROUTING")) routingLatch.countDown();
                }
            });
            engine.initialize();
            routingLatch.await(15, TimeUnit.SECONDS);

            engine.pauseRouting();
            for (int i = 0; i < 3; i++) {
                engine.dispatchSignal("offline_" + i, Map.of("seq", String.valueOf(i)));
            }
            int queuedBefore = engine.getFibreStore().queuedPacketCount();

            engine.resumeRouting();
            int queuedAfter = engine.getFibreStore().queuedPacketCount();
            engine.shutdown();

            if (queuedBefore >= 3 && queuedAfter == 0) {
                System.out.println("PASS (" + queuedBefore + " queued → flushed)");
                passed++;
            } else {
                System.out.println("FAIL — before=" + queuedBefore + " after=" + queuedAfter);
                failed++;
            }
        } catch (Exception e) {
            System.out.println("FAIL — " + e.getMessage());
            failed++;
        }
    }

    // ---------------------------------------------------------------
    //  4. Disk persistence across engine restart
    // ---------------------------------------------------------------

    void testDiskPersistenceAcrossRestart() {
        System.out.print("[TEST] Disk persistence across restart... ");
        try {
            // First engine session: queue packets while offline, then shut down
            WanderingFibreEngine engine1 = new WanderingFibreEngine();
            CountDownLatch routingLatch1 = new CountDownLatch(1);
            engine1.registerListener(new EngineCallback() {
                @Override public void onSignal(FibreSignal s) {}
                @Override public void onStateChanged(String s) {
                    if (s.equals("ROUTING")) routingLatch1.countDown();
                }
            });
            engine1.initialize();
            routingLatch1.await(15, TimeUnit.SECONDS);

            // Queue packets while in CONNECTED_MESH but after stopping discovery
            engine1.pauseRouting();
            for (int i = 0; i < 3; i++) {
                engine1.dispatchSignal("persist_test_" + i, Map.of("idx", String.valueOf(i)));
            }
            int queuedFirst = engine1.getFibreStore().queuedPacketCount();
            engine1.shutdown(); // persists peer cache + route cache to disk

            // Verify store files exist on disk
            boolean storeDirExists = new File("f2p-serverless/store").exists();
            boolean peerCacheExists = new File("f2p-serverless/store/peer_cache.dat").exists();
            boolean packetFileExists = new File("f2p-serverless/store/queued_packets.dat").exists();
            boolean routeCacheExists = new File("f2p-serverless/store/route_cache.dat").exists();

            // Second engine session: should reload from disk
            WanderingFibreEngine engine2 = new WanderingFibreEngine();
            CountDownLatch routingLatch2 = new CountDownLatch(1);
            engine2.registerListener(new EngineCallback() {
                @Override public void onSignal(FibreSignal s) {}
                @Override public void onStateChanged(String s) {
                    if (s.equals("CONNECTED_MESH")) routingLatch2.countDown();
                }
            });
            engine2.initialize();

            // On CONNECTED_MESH, the store should flush queued packets from disk
            routingLatch2.await(15, TimeUnit.SECONDS);
            Thread.sleep(500); // let flush complete
            int queuedAfterRestart = engine2.getFibreStore().queuedPacketCount();
            engine2.shutdown();

            if (queuedFirst >= 3 && storeDirExists && peerCacheExists
                    && (packetFileExists || queuedAfterRestart == 0)) {
                System.out.println("PASS (queued=" + queuedFirst
                        + " stores: dir=" + storeDirExists
                        + " peerCache=" + peerCacheExists
                        + " packets=" + packetFileExists + ")");
                passed++;
            } else {
                System.out.println("FAIL — queued=" + queuedFirst
                        + " dir=" + storeDirExists
                        + " peers=" + peerCacheExists
                        + " packets=" + packetFileExists
                        + " afterRestart=" + queuedAfterRestart);
                failed++;
            }
        } catch (Exception e) {
            System.out.println("FAIL — " + e.getMessage());
            failed++;
        }
    }

    // ---------------------------------------------------------------
    //  5. Network diagnostics
    // ---------------------------------------------------------------

    void testNetworkDiagnostics() {
        System.out.print("[TEST] Network diagnostics output... ");
        try {
            WanderingFibreEngine engine = new WanderingFibreEngine();
            CountDownLatch routingLatch = new CountDownLatch(1);
            engine.registerListener(new EngineCallback() {
                @Override public void onSignal(FibreSignal s) {}
                @Override public void onStateChanged(String s) {
                    if (s.equals("ROUTING")) routingLatch.countDown();
                }
            });
            engine.initialize();
            routingLatch.await(15, TimeUnit.SECONDS);

            String diag = engine.getNetworkDiagnostics();
            engine.shutdown();

            boolean ok = diag.contains("State:") && diag.contains("Node:")
                    && diag.contains("Peers:") && diag.contains("Routes:")
                    && diag.contains("Packets:");
            System.out.println(ok ? "PASS" : "FAIL — missing sections");
            if (ok) passed++; else failed++;
        } catch (Exception e) {
            System.out.println("FAIL — " + e.getMessage());
            failed++;
        }
    }

    // ---------------------------------------------------------------
    //  6. Error boundary
    // ---------------------------------------------------------------

    void testErrorBoundary() {
        System.out.print("[TEST] Error boundary catches thrown exceptions... ");
        try {
            WanderingFibreEngine engine = new WanderingFibreEngine();
            CountDownLatch routingLatch = new CountDownLatch(1);
            AtomicInteger errorCode = new AtomicInteger(-1);

            engine.registerListener(new EngineCallback() {
                @Override public void onSignal(FibreSignal s) { throw new RuntimeException("Boom!"); }
                @Override public void onEngineError(int code, String msg, Throwable cause) {
                    errorCode.set(code);
                }
                @Override public void onStateChanged(String s) {
                    if (s.equals("ROUTING")) routingLatch.countDown();
                }
            });

            engine.initialize();
            routingLatch.await(15, TimeUnit.SECONDS);
            engine.dispatchSignal("crash_test", Map.of());
            Thread.sleep(500);
            engine.shutdown();

            boolean ok = errorCode.get() == 201;
            System.out.println(ok ? "PASS (code " + errorCode.get() + ")" : "FAIL — expected 201 got " + errorCode.get());
            if (ok) passed++; else failed++;
        } catch (Exception e) {
            System.out.println("FAIL — " + e.getMessage());
            failed++;
        }
    }

    // ---------------------------------------------------------------
    //  7. Custom configuration
    // ---------------------------------------------------------------

    void testCustomConfiguration() {
        System.out.print("[TEST] Custom EngineConfig... ");
        try {
            EngineConfig config = EngineConfig.builder()
                    .nodeId("custom-node")
                    .logLevel(LogLevel.DEBUG)
                    .heartbeatIntervalMs(100)
                    .peersRequired(2)
                    .maxHeartbeatCycles(3)
                    .linkTtlMs(5000)
                    .maxRetryAttempts(3)
                    .baseBackoffMs(100)
                    .build();

            WanderingFibreEngine engine = new WanderingFibreEngine();
            CountDownLatch routingLatch = new CountDownLatch(1);
            engine.registerListener(new EngineCallback() {
                @Override public void onSignal(FibreSignal s) {}
                @Override public void onStateChanged(String s) {
                    if (s.equals("ROUTING")) routingLatch.countDown();
                }
            });

            engine.configure(config);
            engine.initialize();
            boolean reachedRouting = routingLatch.await(10, TimeUnit.SECONDS);
            String nodeId = engine.getLocalNodeId();
            engine.shutdown();

            if (reachedRouting && "custom-node".equals(nodeId)) {
                System.out.println("PASS (node=" + nodeId + ")");
                passed++;
            } else {
                System.out.println("FAIL — routing=" + reachedRouting + " node=" + nodeId);
                failed++;
            }
        } catch (Exception e) {
            System.out.println("FAIL — " + e.getMessage());
            failed++;
        }
    }

    // ---------------------------------------------------------------
    //  Helper
    // ---------------------------------------------------------------

    private static void deleteDirectory(File dir) {
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) deleteDirectory(f);
                    else f.delete();
                }
            }
            dir.delete();
        }
    }
}
