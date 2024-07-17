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
{"id":"2b142890-088a-4c1a-bec0-83c587978050","temp":41.855612831361164,"time":"2024-03-19 19:40:27.767962","scale":"C","filler":""}
```
The filler can be added to the messages, this is a random set of numbers. The
API provides for the following:

| Endpoint                    | Description                                                |
|-----------------------------|------------------------------------------------------------|
| /                           | home page, no functionality                                |
| /publisher/start            | start publishing messages                                  |
| /publisher/stop             | stop publishing messages                                   |
| /publisher/pause            | pause publishing messages                                  |
| /publisher/resume           | resume publishing messages                                 |
| /publisher/lowersleep       | decrease time between publishing messages by one second    |
| /publisher/raisesleep       | increase time between publishing messages by one second    |
| /publisher/lowermessages    | reduce messages per publish by one                         |
| /publisher/raisemessages    | increase messages per publish by one                       |
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

