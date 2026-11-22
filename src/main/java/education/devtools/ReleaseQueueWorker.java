package education.devtools;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ReleaseQueueWorker {
    private final InfraiQueueClient queue;
    private final WorkerConfiguration configuration;
    private final ReleaseOperation releases;
    private final WindowRateLimiter limiter;

    public ReleaseQueueWorker(InfraiQueueClient queue, WorkerConfiguration configuration, ReleaseOperation releases) {
        this.queue = queue;
        this.configuration = configuration;
        this.releases = releases;
        this.limiter = new WindowRateLimiter(configuration.releasesPerWindow(), configuration.rateWindow(), Clock.systemUTC());
    }

    public List<ReleaseDiagnostic> runOnce() throws IOException, InterruptedException {
        int allowance = limiter.availablePermits();
        if (allowance == 0) return List.of();
        int batchSize = Math.min(configuration.concurrency(), allowance);
        List<InfraiQueueClient.QueueMessage> messages = queue.consume(batchSize, configuration.visibilityTimeoutSeconds());
        if (messages.isEmpty()) return List.of();
        limiter.reserve(messages.size());

        ExecutorService executor = Executors.newFixedThreadPool(configuration.concurrency());
        try {
            List<Future<ReleaseDiagnostic>> work = messages.stream()
                    .map(message -> executor.submit(() -> releaseAndAcknowledge(message)))
                    .toList();
            return work.stream().map(ReleaseQueueWorker::completed).toList();
        } finally {
            executor.shutdown();
        }
    }

    private ReleaseDiagnostic releaseAndAcknowledge(InfraiQueueClient.QueueMessage message) throws Exception {
        ReleaseDiagnostic diagnostic = releases.release(message.event());
        queue.acknowledge(message.messageId());
        return diagnostic;
    }

    private static ReleaseDiagnostic completed(Future<ReleaseDiagnostic> future) {
        try { return future.get(); }
        catch (Exception exception) { throw new IllegalStateException("Release task failed", exception); }
    }

    @FunctionalInterface
    public interface ReleaseOperation {
        ReleaseDiagnostic release(InfraiQueueClient.BuildEvent event) throws Exception;
    }

    public record ReleaseDiagnostic(String courseId, String commitSha, String outcome) {}

    static final class WindowRateLimiter {
        private final int limit;
        private final Duration window;
        private final Clock clock;
        private Instant windowStarted;
        private int reserved;

        WindowRateLimiter(int limit, Duration window, Clock clock) {
            this.limit = limit;
            this.window = window;
            this.clock = clock;
            this.windowStarted = clock.instant();
        }

        synchronized int availablePermits() {
            refresh();
            return limit - reserved;
        }

        synchronized void reserve(int count) {
            refresh();
            if (count < 0 || count > limit - reserved) throw new IllegalArgumentException("Rate limit exceeded");
            reserved += count;
        }

        private void refresh() {
            Instant now = clock.instant();
            if (!now.isBefore(windowStarted.plus(window))) {
                windowStarted = now;
                reserved = 0;
            }
        }
    }
}
