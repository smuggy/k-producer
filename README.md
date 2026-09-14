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

```shell
export SPRING_PROFILES_ACTIVE=consul
```

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
