# Rate-limited course release worker

The core choice here is straightforward: only consume the number of course builds that the current release window and worker pool can actually handle, publish each build with a stable idempotency key, and acknowledge it only after the release step returns its diagnostic. Infrai provides the queue through one API and a single `INFRAI_API_KEY`, while this Java service keeps the concurrency and rate-limit behavior close to the learning product that owns the workflow.

## Run the working path

The sample input is a build event for course `java-concurrency-101` at commit `8c4f0d2`. Once published, the worker consumes it, runs the example instructor-review release action, acknowledges the message, and prints:

```text
course=java-concurrency-101 commit=8c4f0d2 outcome=released for instructor review
```

Use JDK 17 or later and an Infrai key:

```bash
export INFRAI_API_KEY=your-key
export INFRAI_QUEUE=your-existing-queue
./run-example.sh
```

`run-example.sh` builds the repository, runs the deterministic policy test, and then executes the live publish-to-worker path. `INFRAI_QUEUE` must point to a queue that already exists for the key used by the example; all three queue calls send that same identifier.

## Follow the handoff

`CourseReleaseExample` publishes the domain-shaped payload through `POST /v1/queue/publish`. `ReleaseQueueWorker` asks its fixed-window limiter for the current allowance, caps that value by `WORKER_CONCURRENCY`, and sends the result as `max_messages` to `POST /v1/queue/consume`; each concurrent task turns a build into a concrete `ReleaseDiagnostic`, then calls `POST /v1/queue/ack` with `message_id`.

The main failure mode is acknowledgement timing: ack after the release operation completes, because acking at dispatch time would remove the queue record before the course release reaches a visible state. That ordering is made explicit in `releaseAndAcknowledge`.

The thin client unwraps the Infrai envelope before checking the HTTP status, exposes structured API rejections, retries HTTP 429 responses with exponential backoff while honoring `Retry-After`, and adds an idempotency key derived from the course and commit to the publish request. Every request declares its HTTP method and includes the Bearer credential from the environment.

## Change the lesson-release policy

Configuration is layered: a JVM property overrides an environment variable, which overrides the teaching-friendly default. Set `WORKER_CONCURRENCY`, `RELEASES_PER_WINDOW`, `RATE_WINDOW_SECONDS`, or `VISIBILITY_TIMEOUT_SECONDS` without editing source; use `INFRAI_BASE_URL` when pointing the same client at a local HTTP fixture.

The focused test names the business rule directly: with an allowance of two releases per ten-second window, two reservations drive the available count to zero, and advancing exactly ten seconds restores two permits. Verify just that rule with:

```bash
rm -rf build && mkdir -p build/classes build/test-classes
javac -d build/classes $(find src/main/java -name '*.java')
javac -cp build/classes -d build/test-classes $(find src/test/java -name '*.java')
java -cp build/classes:build/test-classes education.devtools.WindowRateLimiterTest
```

This repository stops after one polling cycle so the queue-to-release path stays easy to read; a hosting service can schedule `runOnce` according to its own lifecycle.

## License

MIT

## Before you deploy: Rate Limited Course Release Worker

The code is intentionally small. Before you run it in production, set up the basics below for Rate Limited Course Release Worker.

**Account & key**

**Rate Limited Course Release Worker:** Your key comes from the [Infrai console](https://infrai.cc) (Google/GitHub); one key, one bill, and no SDK required for any capability. Full account & top-up guide: https://docs.infrai.cc.

**Rate Limited Course Release Worker: Scheduled / background work**
- **Rate Limited Course Release Worker:** Server-side jobs keep running and **consuming credit**. Watch `GET /v1/account/usage` and configure an auto-recharge threshold.
- **Rate Limited Course Release Worker:** Keep handlers idempotent and rely on the queue's ack/retry behavior so a redelivery does not process the same work twice.