# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

k-producer is a Spring Boot utility for load-testing Kafka. It runs as both a producer and consumer of small JSON "temperature" messages, exposing REST endpoints to control message rate/size/count at runtime and to inspect end-to-end latency between message creation and consumption. See README.md for the full endpoint table and message format.

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

### Reader/Writer abstraction

The app is built around two small interface pairs that decouple message transport from message content:

- `MessageWriter.writeMessage(String)` / `MessageReader.readMessage(): List<String>` — the transport. Implementations: `KafkaWriter`/`KafkaReader` (real Kafka via `spring-kafka`), `QueueManager` (in-memory `BlockingQueue`, implements both interfaces, used for local testing without Kafka), `ConsoleWriter`/`EmptyWriter`/`EmptyReader` (no-op transports).
- `MessageGenerator.createMessage(): String` / `MessageConsumer<T>.getMessage(String): Optional<Pair<T,Integer>>` — the payload. Currently only `Temperature`/`TemperatureGenerator`/`TemperatureConsumer` exist, producing/parsing the JSON temperature reading described in README.md.

Which transport is wired up is controlled entirely by the `myapp.messenger` property (`kafka` | `console` | `queue` | anything else → empty no-op), read in `AppConfig`. All the `@Bean` methods for readers/writers/factories in `AppConfig` branch on this value — when adding a new transport, follow that same pattern rather than introducing conditional logic elsewhere.

### Producer/consumer runtime loops

- `Publisher` (implements `Runnable` + `PublisherManager` for JMX) drives message generation: runs on a single-thread `ExecutorService`, loops calling `generator.createMessage()` / `writer.writeMessage()` at a configurable interval (`halfSeconds` — note the API is in half-second units, exposed via `/publisher/lowersleep`/`raisesleep` etc.), with `pause`/`quit` flags checked each loop iteration.
- `Watcher<T>` is the consumer-side mirror: loops calling `reader.readMessage()`, parses each message via `MessageConsumer<T>`, and — if a `BlockingQueue<ValueEnvelope<T>>` has been set via `setReturnQueue` — pushes a timestamped `ValueEnvelope` for latency measurement.
- Both classes share the same start/stop/pause/quit/teardown lifecycle shape but do **not** share a common base class or interface; keep that in mind when modifying one — the other needs the equivalent change made independently.
- `PublisherController` (`/publisher/*`) and `ConsumerController` (`/consumer/*`) are thin REST wrappers around a single injected `Publisher`/`Watcher` bean (both Spring singletons wired in `AppConfig`). `ConsumerController` also owns the latency-tracking `BlockingQueue` and computes `/consumer/stats` and `/consumer/histogram` from it.

### Configuration (Spring profiles)

`application.yaml` uses YAML multi-document profiles (`spring.config.activate.on-profile`): `default,dev` (local, Consul disabled), `test` (Consul at `consul.ps.internal`), `consul` (Consul at `localhost:8500`). Kafka connection, topic, group id, and publisher defaults (`myapp.*`) are set per-environment here — check which profile is active before assuming a given `bootstrapAddress`/`topicName` is in effect.

### JMX management

`ManagementAgentImpl` registers `MBeanContainer`-wrapped beans (e.g. the `Publisher`, exposed as `PublisherManager`) onto the platform `MBeanServer` under the `net.podspace.jmx:type=KPAgent,...` naming convention, giving an alternate (JMX-based) control surface alongside the REST endpoints.

### Logging

Log4j2 is used exclusively (`spring-boot-starter-logging` is excluded in `build.gradle`); JSON layout config is in `src/main/resources/log4j2.xml` / `JsonLayout.json`.
