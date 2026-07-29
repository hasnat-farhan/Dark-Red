package com.antor.f2p.engine.test;

import com.antor.f2p.engine.api.EngineCallback;
import com.antor.f2p.engine.api.EngineState;
import com.antor.f2p.engine.api.FibreSignal;
import com.antor.f2p.engine.api.WanderingFibreEngine;
import com.antor.f2p.engine.api.FibrePacket;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Validation test utility that simulates the full lifecycle of the
 * Wandering Fibre Engine:
 * <ol>
 *   <li>Create and initialise the engine</li>
 *   <li>Wait for peer discovery → CONNECTED_MESH → ROUTING</li>
 *   <li>Dispatch a signal and verify it is received as a {@link FibrePacket}</li>
 *   <li>Shut down gracefully</li>
 * </ol>
 *
 * <p>Run from the project root with:
 * <pre>{@code
 * # Compile all engine sources and run the validation test:
 * javac -d out $(find f2p-serverless/wandering-fibre-engine -name '*.java')
 * java -cp out com.antor.f2p.engine.test.ValidationRunner
 * }</pre>
 * </p>
 */
public class ValidationRunner {

    private static final int TIMEOUT_SECONDS = 15;

    private final WanderingFibreEngine engine;
    private final AtomicReference<EngineState> currentState;
    private final AtomicInteger signalCount;
    private final AtomicInteger packetCount;
    private final CountDownLatch meshLatch;
    private final CountDownLatch routingLatch;
    private final CountDownLatch signalLatch;
    private final CountDownLatch packetLatch;

    public ValidationRunner() {
        this.engine = new WanderingFibreEngine();
        this.currentState = new AtomicReference<>(EngineState.UNINITIALIZED);
        this.signalCount = new AtomicInteger(0);
        this.packetCount = new AtomicInteger(0);
        this.meshLatch = new CountDownLatch(1);
        this.routingLatch = new CountDownLatch(1);
        this.signalLatch = new CountDownLatch(1);
        this.packetLatch = new CountDownLatch(1);
    }

    /**
     * Runs the full validation sequence.
     *
     * @return 0 on success, 1 on failure
     */
    public int run() {
        try {
            // ---------------------------------------------------------
            // Phase 1: Register listener & initialise
            // ---------------------------------------------------------
            System.out.println("[VALIDATION] ========================================");
            System.out.println("[VALIDATION] Wandering Fibre Engine — Validation Run");
            System.out.println("[VALIDATION] ========================================");
            System.out.println();

            engine.setLocalNodeId("validation-node-01");
            engine.registerListener(new EngineCallback() {
                @Override
                public void onSignal(FibreSignal signal) {
                    System.out.println("[CALLBACK] Signal received: " + signal);
                    signalCount.incrementAndGet();
                    signalLatch.countDown();
                }

                @Override
                public void onPacketReceived(FibrePacket packet) {
                    System.out.println("[CALLBACK] Packet received: " + packet
                            + " | payload size=" + packet.getRawDataBuffer().length + " bytes");
                    packetCount.incrementAndGet();
                    packetLatch.countDown();
                }

                @Override
                public void onStateChanged(String newState) {
                    EngineState previous = currentState.getAndSet(EngineState.valueOf(newState));
                    System.out.println("[CALLBACK] State change: " + previous + " -> " + newState);
                    if (EngineState.valueOf(newState) == EngineState.CONNECTED_MESH) {
                        meshLatch.countDown();
                    }
                    if (EngineState.valueOf(newState) == EngineState.ROUTING) {
                        routingLatch.countDown();
                    }
                }

                @Override
                public void onStateChanged(EngineState previous, EngineState current) {
                    // Already handled by the String overload above
                }

                @Override
                public void onError(Throwable throwable) {
                    System.err.println("[CALLBACK] Error: " + throwable.getMessage());
                }
            });

            System.out.println("[VALIDATION] Initialising engine...");
            engine.initialize();
            System.out.println("[VALIDATION] Engine initialised. Waiting for mesh...");

            // ---------------------------------------------------------
            // Phase 2: Wait for peer discovery → mesh → routing
            // ---------------------------------------------------------
            boolean meshReady = meshLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!meshReady) {
                System.err.println("[FAIL] Timed out waiting for CONNECTED_Mesh");
                return 1;
            }
            System.out.println("[VALIDATION] ✓ Mesh established (" + currentState.get() + ")");

            boolean routingReady = routingLatch.await(5, TimeUnit.SECONDS);
            if (!routingReady) {
                System.err.println("[FAIL] Timed out waiting for ROUTING");
                return 1;
            }
            System.out.println("[VALIDATION] ✓ Routing active (" + currentState.get() + ")");
            System.out.println();

            // ---------------------------------------------------------
            // Phase 3: Dispatch a signal & verify packet delivery
            // ---------------------------------------------------------
            System.out.println("[VALIDATION] Dispatching test signal...");
            Map<String, Object> payload = new HashMap<>();
            payload.put("_destination", "broadcast");
            payload.put("message", "Hello from validation!");
            payload.put("origin", "ValidationRunner");

            engine.dispatchSignal("test_ping", payload);

            boolean signalDelivered = signalLatch.await(3, TimeUnit.SECONDS);
            if (!signalDelivered) {
                System.err.println("[FAIL] Signal callback not fired");
                return 1;
            }
            System.out.println("[VALIDATION] ✓ Signal callback received");

            boolean packetDelivered = packetLatch.await(3, TimeUnit.SECONDS);
            if (!packetDelivered) {
                System.err.println("[FAIL] Packet callback not fired");
                return 1;
            }
            System.out.println("[VALIDATION] ✓ Packet callback received (converted from signal)");
            System.out.println();

            // ---------------------------------------------------------
            // Phase 4: Report statistics
            // ---------------------------------------------------------
            System.out.println("[VALIDATION] ========== Statistics ==========");
            System.out.println("[VALIDATION] Final state:      " + engine.getState());
            System.out.println("[VALIDATION] Signals received: " + signalCount.get());
            System.out.println("[VALIDATION] Packets received: " + packetCount.get());
            System.out.println("[VALIDATION] Routing table:    " + engine.getRoutingTable().getOutboundCount() + " outbound");
            System.out.println("[VALIDATION]                    " + engine.getRoutingTable().getInboundCount() + " inbound");
            System.out.println("[VALIDATION] ========================================");
            System.out.println();
            System.out.println("[VALIDATION] ✓ ALL VALIDATIONS PASSED");
            return 0;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[FAIL] Interrupted: " + e.getMessage());
            return 1;
        } finally {
            engine.shutdown();
            System.out.println("[VALIDATION] Engine shut down.");
        }
    }

    // ---------------------------------------------------------------
    //  Entry point
    // ---------------------------------------------------------------

    public static void main(String[] args) {
        int exitCode = new ValidationRunner().run();
        System.exit(exitCode);
    }
}
