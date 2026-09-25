# k-producer

## Overview
Tool to test Kafka. Act as both a producer and consumer of simple messages, with the ability
to increase throughput, size, and frequency of the messages. Provide an endpoint that
shows time between message creation and consumption. 

## Feature List

Done:

* configs in Consul, and running with no Consul at all
* start/stop/pause the message stream at runtime
* change rate, batch size and message size at runtime
* containerised, multi-architecture
* end-to-end latency as a Prometheus histogram
* delivery reconciliation — sent vs received, with loss, duplicates and reordering
* round-trip measurement across hosts via the origin/echo roles, needing no clock synchronisation
* partition keys, for deliberate placement across partitions
* verification mode with a pass/fail exit code, for use as a build step
* liveness/readiness health contributors per pipeline engine

Not done:

* **Avro payloads via a schema registry.** Today messages are hand-built JSON: `Temperature`
  writes its own `toJsonString()` and `TemperatureConsumer` parses it with Jackson, over
  `StringSerializer`/`StringDeserializer`. That is deliberate in one respect - it keeps the tool
  dependency-free and readable on the wire with `kafka-console-consumer` - but it means the probe
  exercises none of the schema machinery a real deployment relies on, and a registry that is slow,
  unreachable or rejecting an incompatible schema is a genuine production failure mode this tool
  cannot currently reproduce.

  Worth doing because it widens what the tool measures: serialization cost as a share of end-to-end
  latency, the registry as an availability dependency alongside the brokers, and schema-evolution
  behaviour on a live topic. It would also make the probe representative of clusters where Avro is
  the norm rather than the exception.

  Three constraints any implementation has to respect, all of them load-bearing:
  * **The `run` and `seq` fields must survive.** Delivery reconciliation is built on them, so they
    belong in the schema, not alongside it.
  * **The embedded timestamp must not be rewritten.** `Relay` forwards messages verbatim as strings
    precisely so the origin's timestamp reaches the origin unchanged; re-serializing through an
    Avro record in the relay would destroy the round-trip measurement. Either the relay keeps
    handling opaque bytes, or echo mode breaks.
  * **It must stay optional.** `myapp.messenger` already selects the transport; payload format wants
    the same treatment, so the queue and no-op transports and a registry-less cluster keep working.
    The `MessageReader`/`MessageWriter` SPI is `String`-based, so byte-oriented payloads mean either
    widening that SPI or Base64-ing through it - the former is cleaner and the change is contained,
    since only Kafka and the in-memory queue implement it.
* JSON output from the interactive endpoints (verification mode covers the scripted case)
* topic administration — both topics must already exist, or the brokers must allow auto-creation.
  Two routes, and they are not alternatives so much as different scopes:
  * **`KafkaAdmin` in the application**, for creating a topic on demand at start-up. Useful for
    throwaway runs against a cluster you do not own the terraform for. (Terraform provisioning is
    done — see below.)
* SASL/TLS to the brokers: the Kafka client configuration is built in code and has no passthrough
  for security properties, so only PLAINTEXT is reachable today
* measuring how long recovery takes after an outage, as distinct from detecting one

## Description
Utility to publish to Kafka or consume from Kafka. Using the same
Kafka configuration for both publish and consume. The publisher 
creates messages on a regular basis (every 5 seconds) upon initiation of
a small message (<150 bytes). That looks like:
```json
{"id":"2b142890-088a-4c1a-bec0-83c587978050","temp":41.855612831361164,"time":"2024-03-19T19:40:27.767962Z","scale":"C","filler":""}
```
The filler can be added to the messages, this is a random set of numbers. `time` is an ISO-8601
instant in UTC — it must be zone-explicit, since producer and consumer may sit in different zones.

## Roles

Latency is measured by subtracting the timestamp written into the message from the timestamp taken
when it is consumed. **Both readings have to come from the same clock**, or the result is clock
skew rather than latency — and between availability zones the skew is routinely larger than the
latency being measured. `myapp.role` decides how that constraint is satisfied.

| Role                 | Publishes to    | Consumes        | Measures                                        |
|----------------------|-----------------|-----------------|-------------------------------------------------|
| `loopback` (default) | `topicName`     | `topicName`     | one-way latency, single process                 |
| `origin`             | `topicName`     | `echoTopicName` | **round trip**, timed entirely on its own clock |
| `echo`               | `echoTopicName` | `topicName`     | nothing — it is a relay                         |

**`loopback`** is the original behaviour: one process both publishes and consumes, so the two
timestamps share a clock. Valid only as a single instance — scaling out does not fail, it quietly
reports skew as latency. A warning is logged at startup if service discovery sees more than one
instance.

**`origin` + `echo`** is how to measure across zones or hosts. The origin publishes to one topic
and listens on the other; the echo instance relays between them. Because the origin stamps the
message and later reads its own stamp back, **no clock synchronisation is required** — the result
is a genuine round trip, unaffected by skew between the two machines.

```
  origin (AZ-a)                            echo (AZ-b)
    publish  ──► test-topic-one   ─────────►  consume
    consume  ◄── test-topic-echo  ◄─────────  republish
    │
    └── round-trip latency, measured on one clock
```

```shell
# AZ-a
SPRING_APPLICATION_JSON='{"myapp":{"role":"origin"}}' ./gradlew bootRun
# AZ-b
SPRING_APPLICATION_JSON='{"myapp":{"role":"echo"}}' ./gradlew bootRun
```

Both topics must already exist (or the brokers must allow auto-creation), and the echo instance
starts relaying on its own — there is nothing to trigger.

Notes:

* Latency metrics are tagged `role`, so one-way and round-trip samples never share a series.
* Halving a round trip to estimate one-way assumes a symmetric path. The two topics' partition
  leaders can sit on different brokers, so the legs may not be symmetric — the round trip is the
  trustworthy figure.
* Start-up fails fast on a configuration that would loop: `echoTopicName` equal to `topicName`, or
  the `echo` role on a transport whose reader and writer are the same channel (`messenger=queue`).
  Either would make the relay re-consume its own output and amplify without bound.

The API provides for the following (plus the actuator endpoints, notably
`/actuator/info` for the running build, `/actuator/health` and `/actuator/prometheus`):

| Endpoint                   | Description                                                 |
|----------------------------|-------------------------------------------------------------|
| /publisher/start           | start publishing messages                                   |
| /publisher/stop            | stop publishing messages                                    |
| /publisher/pause           | pause publishing messages                                   |
| /publisher/resume          | resume publishing messages                                  |
| /publisher/lowersleep      | decrease time between publishing messages by a half second  |
| /publisher/raisesleep      | increase time between publishing messages by a half second  |
| /publisher/lowermessages   | reduce messages per publish by five (floor of one)          |
| /publisher/raisemessages   | increase messages per publish by five                       |
| /publisher/lowerfillersize | decrease byte size of filler by 512                         |
| /publisher/raisefillersize | increase byte size of filler by 512                         |
| /publisher/settings        | current rate, batch size and filler size                    |
| /consumer/start            | start consuming messages                                    |
| /consumer/stop             | stop consuming messages                                     |
| /consumer/pause            | pause consuming messages                                    |
| /consumer/resume           | resume consuming messages                                   |
| /consumer/stats            | display message time for each message                       |
| /consumer/histogram        | display a table with number of messages in each time range  |
| /consumer/reconciliation   | sent-vs-received reconciliation; `?finalize=true` to settle |

The publisher and consumer will create a new thread that will independently process
and create/consume messages from the web server capability.

## Verification mode — running this as a build step

The endpoints above answer "what is happening now". Verification mode answers "did it pass", which
is what a pipeline gate needs. Setting `myapp.verify.messages` publishes that many messages, waits
for the pipeline to drain, reconciles, prints a summary and exits with a status code:

```shell
java -jar k-producer.jar \
    --myapp.kafka.bootstrapAddress=kafka-00:9092 \
    --myapp.kafka.topicName=test-topic-one \
    --myapp.verify.messages=5000
```

| Exit | Meaning                                                                  |
|------|--------------------------------------------------------------------------|
| 0    | every message published was delivered                                    |
| 1    | messages were lost — the cluster was given them and did not deliver them |
| 2    | inconclusive; the run could not be completed, so no verdict is claimed   |

**Inconclusive is deliberately not a failure code.** A build that fails because messages were lost
and one that fails because the brokers were unreachable call for different responses, and
collapsing both into "non-zero" hides that. A run is inconclusive when the consumer never receives
a partition assignment, when the target volume could not be published, when sends failed locally
(so the cluster was never given anything), or when messages are still in flight at the deadline.

Without `myapp.verify.messages` the application behaves exactly as before, so this cannot surprise
a long-running deployment by exiting under it. It needs one process that both publishes and
consumes — the `loopback` role, or `origin` with an echo instance running.

```shell
./kafka-topics.sh --list --bootstrap-server localhost:9092
./kafka-topics.sh --create --topic test-topic-one --partitions 1 --replication-factor 2  --bootstrap-server localhost:9092
./kafka-topics.sh --describe --bootstrap-server localhost:9092
```

## Configuration

The application runs anywhere with no external dependency. Everything it needs is bundled, and
each further layer is optional — in particular **it starts and works without Consul**.

Sources, lowest precedence first:

| # | Source                                      | Optional?      | Use for                                           |
|---|---------------------------------------------|----------------|---------------------------------------------------|
| 1 | `application.yaml` inside the jar           | always present | defaults that work standalone                     |
| 2 | `/config/application.yaml`                  | yes            | per-environment overrides (ConfigMap, bind mount) |
| 3 | Consul KV                                   | yes            | centrally managed / shared configuration          |
| 4 | Environment variables and command-line args | yes            | one-off overrides, secrets                        |

Later layers override earlier ones, so you only supply what differs.

### 1. Standalone

```shell
docker run -p 8080:8080 mmckernan/k-producer:0.5.1
```

No profile, no mounts, no Consul. The bundled defaults point at `localhost:9092`, so nothing in
the jar is tied to a particular network.

`scripts/run-docker.sh` wraps the common invocations, builds the image if it is missing, waits for
health and prints the useful URLs. It takes the image tag from `build.gradle`, so it cannot drift
from the build:

```shell
./scripts/run-docker.sh                                    # standalone, in-memory queue
./scripts/run-docker.sh --broker 192.168.0.60:9092 --start # against Kafka, publishing immediately
./scripts/run-docker.sh --profile test --ca ../vpcs/secrets/internal_ca_cert.pem
./scripts/run-docker.sh --config my-overrides.yaml         # mounts at /config
./scripts/run-docker.sh logs | status | stop
./scripts/run-docker.sh --help
```

### 2. External file

Spring searches `./config/` and the container's working directory is `/`, so a file mounted at
`/config/application.yaml` is picked up **automatically** — no `spring.config.location` needed.
It layers on top of the bundled file rather than replacing it, so partial overrides are enough:

```yaml
# my-overrides.yaml — only what differs
myapp:
  az: us-east-2a
  kafka:
    bootstrapAddress: kafka-00:9092,kafka-01:9092,kafka-02:9092
```

```shell
docker run -p 8080:8080 -v "$PWD/my-overrides.yaml":/config/application.yaml:ro \
    mmckernan/k-producer:0.5.2

kubectl -n app-ns create configmap k-producer-config \
    --from-file=application.yaml=my-overrides.yaml
```

The deployment already mounts a `k-producer-config` ConfigMap if one exists.

### 3. Consul

Select a profile whose Consul endpoint you want:

```shell
export SPRING_PROFILES_ACTIVE=consul      # localhost:8500
export SPRING_PROFILES_ACTIVE=test        # consul.ps.internal:8501 over https
```

Config is read from `config/k-producer,<profile>/`. Note the two profiles deliberately differ in
format, because the underlying data differs: the `test` prefix holds a single `data` key
containing a YAML document (`format: yaml`), while the Terraform-provisioned prefixes hold
individual subkeys (the default KEY_VALUE format). **The format must match how the prefix was
written.**

**Consul is never required.** Three separate settings make that true, and all three are needed —
each covers a different failure:

| Setting                                          | Covers                        |
|--------------------------------------------------|-------------------------------|
| `spring.config.import: "optional:consul:…"`      | no configuration at that path |
| `spring.cloud.consul.config.fail-fast: false`    | Consul unreachable            |
| `spring.cloud.consul.discovery.fail-fast: false` | service registration failing  |

With Consul down the application starts on the layers below it, `/actuator/health` reports
`consul: DOWN` so the degradation is visible, but **liveness and readiness stay UP** — an optional
dependency being absent should not restart the pod or pull it out of service.

Consul serves HTTPS from a private CA. Drop the CA PEM into the directory named by
`CONSUL_CA_DIR` (default `/etc/ssl/consul-ca`) and the entrypoint imports it into a copy of the
JVM trust store at start-up. Mounting rather than baking it in means a CA rotation is a ConfigMap
change and a restart:

```shell
kubectl -n app-ns create configmap consul-ca \
    --from-file=internal-ca.pem=path/to/internal_ca_cert.pem
```

Without the CA the TLS handshake fails; Spring Cloud Consul reports this as
`config data resource ... does not exist` rather than as a certificate error, which is misleading
— check for `PKIX path building failed` in the log.

### 4. Environment variables

Relaxed binding maps any property to an environment variable, which is the right home for
anything environment-specific or sensitive:

```shell
MYAPP_KAFKA_BOOTSTRAPADDRESS=kafka-00:9092
MYAPP_ROLE=origin
SPRING_CLOUD_CONSUL_CONFIG_ACL_TOKEN=...      # never put a real token in application.yaml,
                                              # which is baked into the image
```

### Confirming where a value came from

`/actuator/env/<property>` names the winning source and every candidate:

```shell
curl localhost:8080/actuator/env/myapp.kafka.topicName
```

Values are masked unless `management.endpoint.env.show-values` is set. A metric tag proves the
value actually took effect rather than merely being present — for example the `topic` tag on
`kproducer_producer_acknowledged_total`.

---
## Provisioning topics with Terraform

`terraform/topics.tf` creates every topic the application is configured to use. The names live once
in `main.tf` (`local.topics`) and are read by both the Consul configuration and the `kafka_topic`
resources, so a rename cannot leave the application pointing at a topic that was never created.

```shell
cd terraform
tofu init
tofu apply -target=kafka_topic.probe        # topics only, leaving Consul KV alone
```

| Variable | Default | Notes |
|----------|---------|-------|
| `kafka_bootstrap_servers` | the three `*.podspace.internal` brokers | must be reachable from wherever Terraform runs |
| `topic_partitions` | `3` | more than one is what makes partition behaviour observable |
| `topic_replication_factor` | `3` | at `1` a broker-failure test measures data loss, not failover |
| `topic_min_insync_replicas` | `2` | writes rejected below this, with `acks=all` |
| `topic_retention_ms` | `21600000` (6h) | a probe's output has no value beyond the run |

**Run it from inside the VPC.** The Kafka provider connects to the brokers directly rather than
through an API, and they advertise internal-only names — the same constraint that forces the probe
itself to run there.

A `check` block rejects `min.insync.replicas >= replication_factor` before anything is created:
that combination means every write needs every replica, so losing a single broker halts production
entirely — the opposite of what replication is for.

## Property reference

Every property below is settable by any of the configuration layers above, and each maps to an
environment variable by relaxed binding (`myapp.kafka.bootstrapAddress` →
`MYAPP_KAFKA_BOOTSTRAPADDRESS`).

| Property                        | Default           | Purpose                                                       |
|---------------------------------|-------------------|---------------------------------------------------------------|
| `myapp.messenger`               | `kafka`           | transport: `kafka`, `queue`, `console`, anything else = no-op |
| `myapp.role`                    | `loopback`        | `loopback`, `origin` or `echo` — see Roles above              |
| `myapp.az`                      | `unknown`         | availability zone, tagged onto every metric                   |
| `myapp.instance`                | `${HOSTNAME}`     | instance identity; emitted as the `probe_instance` label      |
| `myapp.kafka.bootstrapAddress`  | `localhost:9092`  | brokers to bootstrap from                                     |
| `myapp.kafka.topicName`         | `test-topic-one`  | outbound topic (inbound for the `echo` role)                  |
| `myapp.kafka.echoTopicName`     | `test-topic-echo` | return topic; must differ from `topicName`                    |
| `myapp.kafka.groupId`           | `test-group-id`   | consumer group                                                |
| `myapp.kafka.autoOffsetReset`   | `latest`          | `latest` to measure from now; `earliest` to read the backlog  |
| `myapp.kafka.acks`              | `all`             | producer acknowledgement level                                |
| `myapp.kafka.maxBlockMs`        | `10000`           | ceiling on a send that cannot even be buffered                |
| `myapp.kafka.deliveryTimeoutMs` | `40000`           | ceiling on a buffered send that is never acknowledged         |
| `myapp.publisher.sleep`         | `10`              | interval between batches, in **half seconds**                 |
| `myapp.publisher.messageCount`  | `5`               | messages per batch                                            |
| `myapp.publisher.fillerSize`    | `512`             | filler bytes per message                                      |
| `myapp.publisher.keyCount`      | `0`               | distinct partition keys; `0` sends unkeyed                    |
| `myapp.verify.messages`         | *unset*           | enables verification mode; messages to publish                |
| `myapp.verify.timeoutSeconds`   | `120`             | budget for publishing the target                              |
| `myapp.verify.drainSeconds`     | `30`              | budget for the pipeline to drain afterwards                   |
| `myapp.verify.attachSeconds`    | `30`              | budget for the consumer to get a partition assignment         |

### Timeouts, and why the defaults are lower than Kafka's

`maxBlockMs` and `deliveryTimeoutMs` both default well below Kafka's own values (60s and 120s).
Kafka's defaults are tuned for an application that should ride out a blip; this is a probe whose
entire job is to notice the blip. At Kafka's defaults a broker outage produces no signal for a
full minute — the send simply blocks, nothing throws, and health still reports UP.

One constraint to respect when changing `deliveryTimeoutMs`: Kafka rejects a value below
`linger.ms + request.timeout.ms`. Do not assume that floor is 30000 — Kafka 4 changed the
`linger.ms` default from 0 to 5, making the real minimum 30005. A value of exactly 30000 is
rejected, and because the producer is built lazily that surfaces as *every send failing at
runtime* rather than as a startup error.

### Partition keys

`myapp.publisher.keyCount` controls how messages are spread. Left at `0` they are sent unkeyed and
Kafka's sticky partitioner places them, which is right for raw throughput. A positive value cycles
over that many keys, and since Kafka hashes the key to choose a partition, each key always lands on
the same one.

That matters for interpreting the `out of order` figure. Kafka orders **within** a partition only,
so traffic spread over several partitions produces reordering as a matter of course — expected
noise, not a finding. With `keyCount=1` everything pins to a single partition, Kafka's ordering
guarantee covers the whole run, and any reordering reported is a genuine fault.

## Metrics

Everything is exported at `/actuator/prometheus`. All series carry `role`, `az` and
`probe_instance`.

**`probe_instance`, not `instance`.** Prometheus attaches its own `instance` label naming the
scrape target, so a metric exposing that name collides and — under the default
`honor_labels: false` — gets silently renamed to `exported_instance`. A dashboard filtering on
`instance` would then select the scrape target rather than the pod. Emitting `probe_instance`
avoids the collision entirely; the property is still `myapp.instance`.

The delivery counters are the reconciliation figures:

| Series                                | Meaning                                                       |
|---------------------------------------|---------------------------------------------------------------|
| `kproducer_delivery_produced_total`   | sequences issued, i.e. messages the publisher tried to send   |
| `kproducer_delivery_unsent_total`     | sends that failed locally, so the cluster never received them |
| `kproducer_delivery_received_total`   | distinct messages of this run that came back                  |
| `kproducer_delivery_missing_total`    | settled without ever arriving — **this is the loss figure**   |
| `kproducer_delivery_pending`          | issued, neither received nor yet settled — still in flight    |
| `kproducer_delivery_duplicates_total` | delivered more than once                                      |
| `kproducer_delivery_outoforder_total` | arrived below the high-water sequence                         |
| `kproducer_delivery_foreign_total`    | from a previous run, excluded from reconciliation             |

**Compare `missing` against `offered`, not `produced`.** A sequence is issued when the message is
created, before the send is attempted, so a send that fails locally would otherwise sit in
`pending` until the window aged it into `missing` — blaming the cluster for losing something it was
never given. Those are retired as `unsent` instead, and `/consumer/reconciliation` reports
`offered` = produced − unsent: what the cluster was actually asked to carry.

Latency is recorded only for messages belonging to the current run. Backlog left on the topic by
an earlier run carries that run's timestamps, so measuring it would report how long a message sat
on the topic rather than anything about the pipeline — and because a timer's histogram is
cumulative, one replay would poison the figures for the life of the process. Those messages are
still counted, as `foreign`.

## Statistics
### Producer side
* Throughput - 
* Request latency - time to get ack
* Retry rate - resilience of producer
* Error rate - fails after retries
* Batch size - 
* Buffer pool utilization - high utilization is generating data faster than it can send
  * if pool fills up it can cause record drops or throttle performance

### Consumer side
* Offset or consumer lag - offset between consumer and producer
  * under provisioned consumers
* Throughput
* Commit rate - how frequently commits occur
* Poll latency - how long to fetch records from broker
* Rebalance count - 


### system-wide
* track offset lag to identify delays in message processing
* monitor producer throughput to ensure data is sent at expected rate
* evaluate end-to-end latency for timely delivery of events
* monitoring error rates to detect serialization or schema issues
* detect rebalancing events that could affect consumer performance

---
# New features
* consumer group?
* delay in reading... mimic slow consumers
* add key to kafka message
