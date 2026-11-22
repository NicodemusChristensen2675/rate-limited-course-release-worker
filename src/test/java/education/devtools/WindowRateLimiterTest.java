package education.devtools;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

public final class WindowRateLimiterTest {
    public static void main(String[] args) {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-08T09:00:00Z"));
        ReleaseQueueWorker.WindowRateLimiter limiter =
                new ReleaseQueueWorker.WindowRateLimiter(2, Duration.ofSeconds(10), clock);

        assertEquals(2, limiter.availablePermits(), "new release window");
        limiter.reserve(2);
        assertEquals(0, limiter.availablePermits(), "two builds consume the teaching-platform release allowance");
        clock.advance(Duration.ofSeconds(10));
        assertEquals(2, limiter.availablePermits(), "the next window admits two more builds");
        System.out.println("PASS: release allowance is enforced and renewed at the window boundary");
    }

    private static void assertEquals(int expected, int actual, String decision) {
        if (expected != actual) throw new AssertionError(decision + ": expected " + expected + ", got " + actual);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
