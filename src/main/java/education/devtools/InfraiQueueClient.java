package education.devtools;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public final class InfraiQueueClient {
    private final WorkerConfiguration configuration;
    private final HttpClient http;

    public InfraiQueueClient(WorkerConfiguration configuration) {
        this(configuration, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    InfraiQueueClient(WorkerConfiguration configuration, HttpClient http) {
        this.configuration = configuration;
        this.http = http;
    }

    public void publish(BuildEvent event) throws IOException, InterruptedException {
        Map<String, Object> payload = Map.of("queue", configuration.queue(), "payload", Map.of(
                "course_id", event.courseId(),
                "commit_sha", event.commitSha(),
                "artifact_uri", event.artifactUri()));
        call("POST", "/v1/queue/publish", payload, idempotencyKey(event));
    }

    public List<QueueMessage> consume(int maxMessages, int visibilityTimeout) throws IOException, InterruptedException {
        Object data = call("POST", "/v1/queue/consume",
                Map.of("queue", configuration.queue(), "max_messages", maxMessages, "visibility_timeout", visibilityTimeout), null);
        if (!(data instanceof Map<?, ?> map) || !(map.get("messages") instanceof List<?> messages)) return List.of();
        return messages.stream().map(this::message).toList();
    }

    public void acknowledge(String messageId) throws IOException, InterruptedException {
        call("POST", "/v1/queue/ack", Map.of("queue", configuration.queue(), "message_id", messageId), null);
    }

    private Object call(String method, String path, Map<String, Object> body, String idempotencyKey)
            throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 5; attempt++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(configuration.baseUri().resolve(path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + configuration.apiKey())
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
            if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());

            Map<?, ?> envelope = envelope(response.body());
            if (response.statusCode() == 429 && attempt < 4) {
                Thread.sleep(retryDelayMillis(response, attempt));
                continue;
            }
            if (!Boolean.TRUE.equals(envelope.get("ok"))) {
                Map<?, ?> error = envelope.get("error") instanceof Map<?, ?> value ? value : Map.of();
                throw new InfraiApiException(response.statusCode(), String.valueOf(error.get("code")), error);
            }
            if (response.statusCode() >= 500) throw new IOException("Infrai transport response " + response.statusCode());
            return envelope.get("data");
        }
        throw new IOException("Retry budget exhausted");
    }

    private static Map<?, ?> envelope(String body) throws IOException {
        try {
            Object decoded = Json.read(body);
            if (decoded instanceof Map<?, ?> map && map.containsKey("ok")) return map;
            throw new IllegalArgumentException("Envelope has no ok field");
        } catch (IllegalArgumentException exception) {
            throw new IOException("Response was not an Infrai envelope", exception);
        }
    }

    private static long retryDelayMillis(HttpResponse<?> response, int attempt) {
        return response.headers().firstValue("Retry-After")
                .map(InfraiQueueClient::retryAfterMillis)
                .orElse(250L * (1L << attempt));
    }

    private static long retryAfterMillis(String value) {
        try { return Math.max(1L, Long.parseLong(value)) * 1000L; }
        catch (NumberFormatException ignored) { return 1000L; }
    }

    private QueueMessage message(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Queue message must be an object");
        Object payloadValue = map.get("payload");
        if (!(payloadValue instanceof Map<?, ?> payload)) throw new IllegalArgumentException("Queue payload must be an object");
        return new QueueMessage(
                requiredString(map, "message_id"),
                new BuildEvent(requiredString(payload, "course_id"), requiredString(payload, "commit_sha"),
                        requiredString(payload, "artifact_uri")));
    }

    private static String requiredString(Map<?, ?> map, String name) {
        Object value = map.get(name);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return text;
    }

    private static String idempotencyKey(BuildEvent event) {
        try {
            byte[] input = (event.courseId() + ":" + event.commitSha()).getBytes(StandardCharsets.UTF_8);
            return "build-event-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record BuildEvent(String courseId, String commitSha, String artifactUri) {}
    public record QueueMessage(String messageId, BuildEvent event) {}

    public static final class InfraiApiException extends IOException {
        private final int statusCode;
        private final String code;
        private final Map<?, ?> detail;

        InfraiApiException(int statusCode, String code, Map<?, ?> detail) {
            super("Infrai request rejected: " + code);
            this.statusCode = statusCode;
            this.code = code;
            this.detail = detail;
        }

        public int statusCode() { return statusCode; }
        public String code() { return code; }
        public Map<?, ?> detail() { return detail; }
    }
}
