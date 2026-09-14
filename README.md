# k-producer

## Overview
Tool to test Kafka. Act as both a producer and consumer of simple messages, with the ability
to increase throughput, size, and frequency of the messages. Provide an endpoint that
shows time between message creation and consumption. 

## Feature List
* configs in Consul - done
* automatic send message for stream 
* ability to start/stop message stream - done
* use avro schema for message
* change rate of messaging - done
* containerize application - done
* send occasional marker?
* size of message - done
* consumer - done
* metrics

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

| Endpoint                    | Description                                                |
|-----------------------------|------------------------------------------------------------|
| /publisher/start            | start publishing messages                                  |
| /publisher/stop             | stop publishing messages                                   |
| /publisher/pause            | pause publishing messages                                  |
| /publisher/resume           | resume publishing messages                                 |
| /publisher/lowersleep       | decrease time between publishing messages by a half second |
| /publisher/raisesleep       | increase time between publishing messages by a half second |
| /publisher/lowermessages    | reduce messages per publish by five (floor of one)         |
| /publisher/raisemessages    | increase messages per publish by five                      |
| /publisher/lowerfillersize  | decrease byte size of filler by 512                        |
| /publisher/raisefillersize  | increase byte size of filler by 512                        |
| /consumer/start             | start consuming messages                                   |
| /consumer/stop              | stop consuming messages                                    |
| /consumer/pause             | pause consuming messages                                   |
| /consumer/resume            | resume consuming messages                                  |
| /consumer/stats             | display message time for each message                      |
| /consumer/histogram         | display a table with number of messages in each time range |

The publisher and consumer will create a new thread that will independently process
and create/consume messages from the web server capability.

```shell
./kafka-topics.sh --list --bootstrap-server localhost:9092
./kafka-topics.sh --create --topic test-topic-one --partitions 1 --replication-factor 2  --bootstrap-server localhost:9092
./kafka-topics.sh --describe --bootstrap-server localhost:9092
```

## Configuration

The application runs anywhere with no external dependency. Everything it needs is bundled, and
each further layer is optional — in particular **it starts and works without Consul**.

Sources, lowest precedence first:

| # | Source | Optional? | Use for |
|---|--------|-----------|---------|
| 1 | `application.yaml` inside the jar | always present | defaults that work standalone |
| 2 | `/config/application.yaml` | yes | per-environment overrides (ConfigMap, bind mount) |
| 3 | Consul KV | yes | centrally managed / shared configuration |
| 4 | Environment variables and command-line args | yes | one-off overrides, secrets |

Later layers override earlier ones, so you only supply what differs.

### 1. Standalone

```shell
docker run -p 8080:8080 mmckernan/k-producer:0.5.1
```

No profile, no mounts, no Consul. The bundled defaults point at `localhost:9092`, so nothing in
the jar is tied to a particular network.

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
    mmckernan/k-producer:0.5.1

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
