package com.antor.f2p.engine.core;

import com.antor.f2p.engine.api.LogLevel;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Non-blocking, asynchronous logger for the Wandering Fibre Engine.
 * <p>
 * Log calls enqueue a formatted message and return immediately. A dedicated
 * daemon thread drains the queue and writes to stdout and a rolling log file
 * under {@code f2p-serverless/logs/}.
 * </p>
 *
 * <p>
 * Log levels ({@link LogLevel}) can be changed at runtime via
 * {@link #setLevel(LogLevel)} without restarting the engine.
 * </p>
 */
public class FibreLogger {

    private static final int QUEUE_CAPACITY = 4096;
    private static final String LOG_DIR = "f2p-serverless/logs";
    private static final String LOG_FILE = LOG_DIR + "/wandering-fibre.log";

    private final BlockingQueue<LogEvent> queue;
    private final AtomicReference<LogLevel> threshold;
    private final AtomicBoolean running;
    private final ThreadLocal<SimpleDateFormat> dateFormat;
    private Thread writerThread;
    private PrintWriter fileWriter;

    /** Singleton instance shared across the engine. */
    private static final FibreLogger INSTANCE = new FibreLogger();

    public static FibreLogger get() { return INSTANCE; }

    private FibreLogger() {
        this.queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        this.threshold = new AtomicReference<>(LogLevel.INFO);
        this.running = new AtomicBoolean(false);
        this.dateFormat = ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm:ss.SSS"));
    }

    // ---------------------------------------------------------------
    //  Lifecycle
    // ---------------------------------------------------------------

    /** Starts the background writer thread. */
    public synchronized void start() {
        if (running.getAndSet(true)) return;
        try {
            new java.io.File(LOG_DIR).mkdirs();
            fileWriter = new PrintWriter(new FileWriter(LOG_FILE, true), true);
        } catch (IOException e) {
            System.err.println("[FibreLogger] Failed to open log file: " + e.getMessage());
            fileWriter = null;
        }
        writerThread = new Thread(this::drainLoop, "fibre-logger-writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    /** Stops the writer thread and flushes remaining entries. */
    public synchronized void stop() {
        running.set(false);
        if (writerThread != null && writerThread.isAlive()) {
            try { writerThread.join(1500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (fileWriter != null) {
            fileWriter.close();
            fileWriter = null;
        }
    }

    // ---------------------------------------------------------------
    //  Runtime level control
    // ---------------------------------------------------------------

    /** Changes the minimum log level at runtime (no restart needed). */
    public void setLevel(LogLevel level) {
        threshold.set(level);
    }

    /** Returns the current minimum log level. */
    public LogLevel getLevel() {
        return threshold.get();
    }

    // ---------------------------------------------------------------
    //  Log methods
    // ---------------------------------------------------------------

    public void trace(String msg)              { log(LogLevel.TRACE, msg, null); }
    public void debug(String msg)              { log(LogLevel.DEBUG, msg, null); }
    public void info(String msg)               { log(LogLevel.INFO, msg, null); }
    public void warn(String msg)               { log(LogLevel.WARN, msg, null); }
    public void warn(String msg, Throwable t)   { log(LogLevel.WARN, msg, t); }
    public void error(String msg)              { log(LogLevel.ERROR, msg, null); }
    public void error(String msg, Throwable t)  { log(LogLevel.ERROR, msg, t); }

    private void log(LogLevel level, String msg, Throwable t) {
        if (!level.isEnabled(threshold.get())) return;

        StringBuilder sb = new StringBuilder(128);
        sb.append(dateFormat.get().format(new Date()));
        sb.append(" [").append(level.name()).append("] ");
        sb.append(Thread.currentThread().getName()).append(" — ");
        sb.append(msg);

        String stackTrace = null;
        if (t != null) {
            StringWriter sw = new StringWriter(256);
            PrintWriter pw = new PrintWriter(sw);
            t.printStackTrace(pw);
            pw.flush();
            stackTrace = sw.toString();
        }

        queue.offer(new LogEvent(sb.toString(), stackTrace));
    }

    // ---------------------------------------------------------------
    //  Drain loop
    // ---------------------------------------------------------------

    private void drainLoop() {
        while (running.get()) {
            try {
                LogEvent event = queue.take();
                write(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        java.util.ArrayList<LogEvent> remaining = new java.util.ArrayList<>();
        queue.drainTo(remaining);
        for (LogEvent e : remaining) write(e);
    }

    private void write(LogEvent event) {
        System.out.println(event.message);
        if (fileWriter != null) {
            fileWriter.println(event.message);
            if (event.stackTrace != null) {
                fileWriter.print(event.stackTrace);
            }
        }
    }

    // ---------------------------------------------------------------
    //  Internal
    // ---------------------------------------------------------------

    private static final class LogEvent {
        final String message;
        final String stackTrace;

        LogEvent(String message, String stackTrace) {
            this.message = message;
            this.stackTrace = stackTrace;
        }
    }
}
