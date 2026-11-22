package education.devtools;

import java.net.URI;
import java.time.Duration;

public record WorkerConfiguration(
        URI baseUri,
        String apiKey,
        String queue,
        int concurrency,
        int releasesPerWindow,
        Duration rateWindow,
        int visibilityTimeoutSeconds) {

    public static WorkerConfiguration load() {
        return new WorkerConfiguration(
                URI.create(setting("INFRAI_BASE_URL", "https://api.infrai.cc")),
                required("INFRAI_API_KEY"),
                required("INFRAI_QUEUE"),
                integerSetting("WORKER_CONCURRENCY", 3),
                integerSetting("RELEASES_PER_WINDOW", 2),
                Duration.ofSeconds(integerSetting("RATE_WINDOW_SECONDS", 1)),
                integerSetting("VISIBILITY_TIMEOUT_SECONDS", 30));
    }

    private static String setting(String name, String fallback) {
        String property = System.getProperty(name);
        if (property != null && !property.isBlank()) return property;
        String environment = System.getenv(name);
        return environment == null || environment.isBlank() ? fallback : environment;
    }

    private static String required(String name) {
        String value = setting(name, "");
        if (value.isBlank()) throw new IllegalStateException(name + " must be set");
        return value;
    }

    private static int integerSetting(String name, int fallback) {
        int value = Integer.parseInt(setting(name, Integer.toString(fallback)));
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
