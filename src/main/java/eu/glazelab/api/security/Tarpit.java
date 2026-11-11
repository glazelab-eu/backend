package eu.glazelab.api.security;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * Answers a flagged scraper slowly with a decoy of random bytes unrelated to its request. Resources are bounded:
 * at most {@code maxConnections} decoys at once (others are refused immediately), one shared chunk in memory,
 * and no thread per decoy. Each decoy is an async response written with non-blocking I/O by one scheduler
 * thread, which skips a tick when the client does not read, and ends after {@code maxBytes} or {@code maxDuration}.
 */
@Component
public class Tarpit implements DisposableBean {
    private final RateLimitSettings.TarpitSettings settings;
    private final Semaphore slots;
    private final byte[] chunk;
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "tarpit");
        thread.setDaemon(true);
        return thread;
    });

    public Tarpit(RateLimitSettings settings) {
        this.settings = settings.tarpit();
        slots = new Semaphore(Math.max(0, this.settings.maxConnections()));
        chunk = new byte[Math.max(1, this.settings.chunkBytes())];
        new Random().nextBytes(chunk);
    }

    /**
     * Starts a decoy response when a slot is free, replacing whatever status and headers the uncommitted response
     * holds; returns false and leaves the response untouched otherwise.
     */
    public boolean start(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!slots.tryAcquire()) return false;
        try {
            response.reset();
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/octet-stream");
            // Never stored by the Nginx image cache or a browser; streamed by Nginx without buffering.
            response.setHeader("Cache-Control", "no-store, private");
            response.setHeader("X-Accel-Buffering", "no");
            AsyncContext async = request.startAsync();
            async.setTimeout(settings.maxDuration().toMillis() + 10_000);
            var decoy = new Decoy(async, async.getResponse().getOutputStream());
            async.addListener(decoy);
            decoy.out.setWriteListener(decoy);
            return true;
        } catch (IOException | RuntimeException e) {
            slots.release();
            throw e;
        }
    }

    /** Decoys currently running (for tests and diagnostics). */
    public int active() { return settings.maxConnections() - slots.availablePermits(); }

    @Override public void destroy() { ticker.shutdownNow(); }

    private final class Decoy implements WriteListener, AsyncListener, Runnable {
        private final AsyncContext async;
        private final ServletOutputStream out;
        private final long deadline = System.nanoTime() + settings.maxDuration().toNanos();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean done = new AtomicBoolean();
        private volatile ScheduledFuture<?> ticks;
        private long sent;

        Decoy(AsyncContext async, ServletOutputStream out) {
            this.async = async;
            this.out = out;
        }

        /** The container calls this first once the request thread has returned; later calls need no action. */
        @Override public void onWritePossible() {
            if (started.compareAndSet(false, true)) {
                long interval = Math.max(1, settings.interval().toMillis());
                ticks = ticker.scheduleAtFixedRate(this, 0, interval, TimeUnit.MILLISECONDS);
                if (done.get()) ticks.cancel(false);
            }
        }

        /** One tick on the scheduler thread: write a chunk if the client takes it, never block. */
        @Override public void run() {
            if (done.get()) return;
            try {
                if (sent >= settings.maxBytes() || System.nanoTime() - deadline >= 0) {
                    finish(true);
                    return;
                }
                if (!out.isReady()) return;
                int length = (int) Math.min(chunk.length, settings.maxBytes() - sent);
                out.write(chunk, 0, length);
                sent += length;
                if (out.isReady()) out.flush();
            } catch (IOException | RuntimeException e) {
                finish(true);
            }
        }

        private void finish(boolean complete) {
            if (!done.compareAndSet(false, true)) return;
            if (ticks != null) ticks.cancel(false);
            slots.release();
            if (complete) {
                try {
                    async.complete();
                } catch (IllegalStateException ignored) {
                    // Already completed by the container (client gone or timeout).
                }
            }
        }

        @Override public void onError(Throwable error) { finish(true); }
        @Override public void onComplete(AsyncEvent event) { finish(false); }
        @Override public void onTimeout(AsyncEvent event) { finish(true); }
        @Override public void onError(AsyncEvent event) { finish(true); }
        @Override public void onStartAsync(AsyncEvent event) {}
    }
}
