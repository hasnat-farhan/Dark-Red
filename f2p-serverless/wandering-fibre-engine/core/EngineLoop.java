package com.antor.f2p.engine.core;

import com.antor.f2p.engine.api.FibrePacket;
import com.antor.f2p.engine.api.FibreSignal;
import com.antor.f2p.engine.api.WanderingFibreEngine;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Adaptive non-blocking execution loop running on a dedicated daemon thread.
 * <p>
 * Both {@link FibreSignal}s and {@link FibrePacket}s are funnelled through
 * a single thread-safe {@link BlockingQueue} and processed asynchronously.
 * The loop uses adaptive polling: when the queue is backlogged it spin-waits
 * for maximum throughput; when idle it sleeps the full poll interval so it
 * never thrashes the CPU.
 * </p>
 */
public class EngineLoop {

    private static final Logger LOG = Logger.getLogger(EngineLoop.class.getName());

    /** Poll timeout when the queue was recently empty (idle behaviour). */
    private static final long IDLE_POLL_MS = 1000;

    /** Poll timeout when the queue had items recently (active behaviour). */
    private static final long ACTIVE_POLL_MS = 50;

    /** After this many consecutive idle polls, the thread may yield. */
    private static final int IDLE_YIELD_THRESHOLD = 10;

    /** Work unit wrapping either a {@link FibreSignal} or a {@link FibrePacket}. */
    public static final class WorkUnit {
        private final FibreSignal signal;
        private final FibrePacket packet;

        private WorkUnit(FibreSignal signal) { this.signal = signal; this.packet = null; }
        private WorkUnit(FibrePacket packet) { this.signal = null; this.packet = packet; }

        public static WorkUnit fromSignal(FibreSignal s) { return new WorkUnit(s); }
        public static WorkUnit fromPacket(FibrePacket p) { return new WorkUnit(p); }

        public boolean isSignal()  { return signal != null; }
        public boolean isPacket()  { return packet != null; }
        public FibreSignal getSignal() {
            if (signal == null) throw new IllegalStateException("Not a signal");
            return signal;
        }
        public FibrePacket getPacket() {
            if (packet == null) throw new IllegalStateException("Not a packet");
            return packet;
        }
    }

    private final BlockingQueue<WorkUnit> queue;
    private final AtomicBoolean running;
    private final FibreProcessor processor;
    private final WanderingFibreEngine engine;

    private Thread loopThread;
    private int consecutiveIdleCycles;

    public EngineLoop(WanderingFibreEngine engine) {
        this.engine = engine;
        this.queue = new LinkedBlockingQueue<>();
        this.running = new AtomicBoolean(false);
        this.processor = new FibreProcessor(engine);
        this.consecutiveIdleCycles = 0;
    }

    /** Starts the background loop thread. */
    public synchronized void start() {
        if (running.getAndSet(true)) return;
        loopThread = new Thread(this::loop, "wandering-fibre-engine-loop");
        loopThread.setDaemon(true);
        loopThread.start();
        LOG.fine("Engine loop started");
    }

    /** Stops the loop and waits for the thread to finish. */
    public synchronized void stop() {
        running.set(false);
        if (loopThread != null && loopThread.isAlive()) {
            try { loopThread.join(2000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        LOG.fine("Engine loop stopped");
    }

    /** Enqueues a signal for async processing. */
    public void enqueue(FibreSignal signal) {
        if (!running.get()) {
            engine.notifyError(new IllegalStateException("Engine loop is not running"));
            return;
        }
        queue.offer(WorkUnit.fromSignal(signal));
    }

    /** Enqueues a packet for async processing. */
    public void enqueuePacket(FibrePacket packet) {
        if (!running.get()) {
            engine.notifyError(new IllegalStateException("Engine loop is not running"));
            return;
        }
        queue.offer(WorkUnit.fromPacket(packet));
    }

    public int pendingCount() { return queue.size(); }

    // ---------------------------------------------------------------
    //  Adaptive polling loop
    // ---------------------------------------------------------------

    private void loop() {
        FibreEngineStateMachine fsm = engine.getFibreEngineStateMachine();

        while (running.get()) {
            try {
                long timeout = (consecutiveIdleCycles > 0) ? IDLE_POLL_MS : ACTIVE_POLL_MS;
                WorkUnit work = queue.poll(timeout, TimeUnit.MILLISECONDS);

                if (work != null) {
                    consecutiveIdleCycles = 0;
                    if (fsm.isRouting() || fsm.isConnected()) {
                        processor.process(work);
                    } else {
                        LOG.fine("Dropping work — engine not in routing/connected: "
                                + fsm.getCurrentState());
                    }
                } else {
                    consecutiveIdleCycles++;
                    if (consecutiveIdleCycles > IDLE_YIELD_THRESHOLD) {
                        Thread.yield(); // hint to scheduler when truly idle
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Unhandled exception in engine loop", e);
                engine.notifyError(e);
            }
        }

        int drained = queue.drainTo(new java.util.ArrayList<>());
        if (drained > 0) {
            LOG.fine("Drained " + drained + " unprocessed items during shutdown");
        }
    }
}
