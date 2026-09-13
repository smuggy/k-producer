# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

k-producer is a Spring Boot probe for exercising and measuring a Kafka cluster. It produces and consumes small JSON "temperature" messages, exposing REST endpoints to vary message rate, size and batch count at runtime, and reporting results as Prometheus metrics. Intended uses include measuring broker and client latency, measuring latency across availability zones (via the `origin`/`echo` roles), and observing behaviour and recovery during broker or infrastructure failures — so **it is expected to keep running through outages**, which is why `WorkerLoop` retries rather than exiting. See README.md for the endpoint table and message format.

## Common commands

```shell
./gradlew build              # compile, run tests, assemble
./gradlew test                # run all tests (JUnit 5 / Jupiter)
./gradlew test --tests "net.podspace.domain.TemperatureTest"        # single test class
./gradlew test --tests "net.podspace.domain.TemperatureTest.TestValidJson"  # single test method
./gradlew bootRun             # run the app locally
```

Run with a specific Spring profile (controls config source — see Configuration below):

```shell
SPRING_PROFILES_ACTIVE=consul ./gradlew bootRun
```

Docker/deploy chain (Gradle tasks in `build.gradle`, run in this dependency order): `prepareDockerDir` → `copyTarToDockerDir` (depends on `build`) → `buildDockerImage` → `tagDockerImage` → `pushDockerImage`. Kubernetes manifests live in `deploy/` (kustomize); Terraform config lives in `src/main/terraform/`.

## Architecture

### Package layout

Packages are organised by role in the pipeline, not by producer/consumer direction:

| Package                                | Contents                                                                                           |
|----------------------------------------|----------------------------------------------------------------------------------------------------|
| `net.podspace`                         | `Main` — must stay in the root package so component scanning reaches every subpackage              |
| `config`                               | `AppConfig` (all bean wiring, role/topic resolution), `SingleInstanceGuard`, `MyBean`              |
| `web`                                  | the three `@RestController`s                                                                       |
| `messaging`                            | the transport SPI: `MessageReader`, `MessageWriter`, `MessageGenerator`, `MessageConsumer`, `Pair` |
| `messaging.kafka` / `.queue` / `.noop` | SPI implementations                                                                                |
| `pipeline`                             | `WorkerLoop`, `Publisher`, `Watcher`, `Relay`, `PublisherManager`, `ValueEnvelope`                 |
| `domain`                               | `Temperature`, `TempScale`, and their generator/consumer                                           |
| `management`                           | JMX agent                                                                                          |

### Reader/Writer abstraction

The app is built around two small interface pairs that decouple message transport from message content:

- `MessageWriter.writeMessage(String)` / `MessageReader.readMessage(): List<String>` — the transport. Implementations: `KafkaWriter`/`KafkaReader` (real Kafka via `spring-kafka`), `QueueManager` (in-memory `BlockingQueue`, implements both interfaces, used for local testing without Kafka), `ConsoleWriter`/`EmptyWriter`/`EmptyReader` (no-op transports).
- `MessageGenerator.createMessage(): String` / `MessageConsumer<T>.getMessage(String): Optional<Pair<T,Integer>>` — the payload. Currently only `Temperature`/`TemperatureGenerator`/`TemperatureConsumer` exist, producing/parsing the JSON temperature reading described in README.md.

Which transport is wired up is controlled entirely by the `myapp.messenger` property (`kafka` | `console` | `queue` | anything else → empty no-op), read in `AppConfig.messageWriter()` / `messageReader()`. Those two are the **only** beans of their types; the per-transport objects and the Kafka factories are plain private methods, deliberately not beans — exposing them as `@Bean` previously forced them to return `null` outside kafka mode and made by-type lookups ambiguous. `QueueManager` is the exception: it must stay a `@Bean` because it implements both interfaces and the writer and reader must share one instance.

### Runtime loops

- **`WorkerLoop`** owns all start/stop/pause machinery: the single-thread `ExecutorService`, the `volatile` `quit`/`pause` flags, `synchronized` `initiate()`/`teardown()`, and an escalating shutdown (`shutdown()` → wait → `shutdownNow()`). Both engines delegate to it and supply only a per-iteration body. **Lifecycle fixes belong here, not in the engines** — they each used to carry their own copy, which drifted.
- `Publisher` supplies `publishBatch()`: emit `messages` messages, then wait `halfSeconds * 500` ms. Note the API is in *half-second* units (`/publisher/lowersleep`/`raisesleep`). It implements `PublisherManager` for JMX.
- `Watcher<T>` supplies `pollAndRecord()`: read, parse via `MessageConsumer<T>`, and hand a timestamped `ValueEnvelope` to the sink set via `setSink`. The sink runs **on the watcher thread**, so it must be cheap and must not throw. `Watcher` does **not** close the reader — the container owns that (see below).
- `Relay` supplies `relayBatch()`: forward every message from the reader to the writer **verbatim, without deserializing**. Re-serializing would rewrite the embedded timestamp and destroy the measurement, and staying at the string level means it relays any payload type.
- Blocking reads/writes must be **bounded and single-attempt**, leaving retries to the loop: only the loop can see the quit flag. An internal retry loop in a reader or writer makes stop hang, since `shutdown()` cannot interrupt.
- `PublisherController` (`/publisher/*`) and `ConsumerController` (`/consumer/*`) are thin REST wrappers over the `Publisher`/`Watcher` singletons. `ConsumerController` also supplies the watcher's sink: it records each latency into a Micrometer `Timer` (`kproducer.message.latency`, tagged by `role`, exported to `/actuator/prometheus`) and keeps only the last 1,000 samples for `/consumer/stats`. The distribution lives in the Timer — do not reintroduce a buffer of whole messages, which is what previously made this a memory risk and silently dropped samples under load.
- The `echo` role has no controller: `Relay` starts itself, since it is a pure pump with nothing to tune.

### Roles and the shared-clock constraint (important)

Latency is a subtraction of two timestamps, so **both must come from the same clock** or the result is skew, not latency. Between availability zones the skew routinely exceeds the latency being measured. `myapp.role` (`AppConfig`) decides how that is satisfied:

| Role                 | Writer topic    | Reader topic    | Notes                                   |
|----------------------|-----------------|-----------------|-----------------------------------------|
| `loopback` (default) | `topicName`     | `topicName`     | one process, one clock; one-way latency |
| `origin`             | `topicName`     | `echoTopicName` | round trip, timed on its own clock      |
| `echo`               | `echoTopicName` | `topicName`     | relay only; `Relay` bean auto-starts    |

`AppConfig.writerTopic()`/`readerTopic()` derive the topics from the role — that crossover is the whole mechanism, so be careful editing them.

**Why echo mode exists:** the origin stamps a message and later reads its own stamp back, so a round trip needs **no clock synchronisation at all**. This is the supported way to measure across zones or hosts. Prefer it over trying to discipline clocks.

`loopback` is still single-instance-only: scaling out silently reports skew as latency. Timestamps are ISO-8601 UTC (`Instant`), so a zone difference no longer corrupts the figure outright, but skew between machines still does. `SingleInstanceGuard` warns at startup when discovery reports more than one instance, but discovery is only enabled on the `test`/`consul` profiles, so it is best-effort.

**Two amplification guards, both fail fast at startup.** Either would make the relay re-consume its own output without bound:
1. `echoTopicName` equal to `topicName` (checked in `validateRole()`).
2. The `echo` role on a transport whose reader and writer are the same object — `messenger=queue` shares one `QueueManager` (checked in the `relay()` bean). The topic check cannot see this case, since no topics are involved.

Anything comparing a produce-side timestamp to a consume-side one inherits the shared-clock constraint. Counting-based checks (delivery reconciliation, per-partition counters) do not — they need no shared clock — but per-sequence gap detection does need one consumer to see the complete sequence stream, which a consumer group spread across instances would not.

### Configuration (Spring profiles)

`application.yaml` uses YAML multi-document profiles (`spring.config.activate.on-profile`): `default,dev` (local, Consul disabled), `test` (Consul at `consul.ps.internal`), `consul` (Consul at `localhost:8500`). Kafka connection, topic, group id, and publisher defaults (`myapp.*`) are set per-environment here — check which profile is active before assuming a given `bootstrapAddress`/`topicName` is in effect.

### JMX management

`ManagementAgentImpl` registers `MBeanContainer`-wrapped beans (e.g. the `Publisher`, exposed as `PublisherManager`) onto the platform `MBeanServer` under the `net.podspace.jmx:type=KPAgent,...` naming convention, giving an alternate (JMX-based) control surface alongside the REST endpoints.

### Logging

Log4j2 is used exclusively (`spring-boot-starter-logging` is excluded in `build.gradle`); JSON layout config is in `src/main/resources/log4j2.xml` / `JsonLayout.json`.
