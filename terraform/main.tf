# The availability zone every instance configured from these prefixes reports. It is tagged onto
# every metric, and it is the dimension that makes an origin in one zone comparable against an echo
# in another - which is the whole point of those two roles. Left unset, instances report
# az="unknown" and become indistinguishable on a dashboard.
#
# One value because all three prefixes below point at the same brokers in the same datacenter;
# they differ by topic and consumer group, not by location. Split this per prefix the moment
# instances actually run somewhere different, or the cross-zone measurement means nothing.
locals {
  availability_zone = "local-net-1"

  # The topic names, defined once. Both the Consul configuration below and the kafka_topic
  # resources in topics.tf read them from here, so a rename cannot leave the application pointing
  # at a topic that was never created.
  topics = {
    ext = {
      main = "test-topic-one"
      echo = "test-topic-one-echo"
    }
    consul = {
      main = "test-topic-three"
      echo = "test-topic-three-echo"
    }
    other = {
      main = "test-topic-two"
      echo = "test-topic-two-echo"
    }
  }
}

# Need to specify export SPRING_PROFILES_ACTIVE="consul,ext"
resource consul_key_prefix app_configuration_ext {
  path_prefix = "config/k-producer,ext/"
  subkeys = {
    "myapp/az"                     = local.availability_zone
    "server/port"                  = "8090"
    "myapp/messenger"              = "kafka"
    "myapp/role"                   = "loopback"
    "myapp/kafka/acks"             = "all"
    "myapp/kafka/topicName"        = local.topics.ext.main
    "myapp/kafka/echoTopicName"    = local.topics.ext.echo
    "myapp/kafka/groupId"          = "test-group-one"
    "myapp/kafka/bootstrapAddress" = "kafka-00:9092,kafka-01:9092,kafka-02:9092"
  }
}


resource consul_key_prefix app_configuration_consul {
  path_prefix = "config/k-producer,consul/"
  subkeys = {
    "myapp/az"                     = local.availability_zone
    "server/port"                  = "8090"
    "myapp/messenger"              = "kafka"
    "myapp/publisher/sleep"        = 2
    "myapp/publisher/fillerSize"   = 1024
    "myapp/publisher/messageCount" = 100
    "myapp/role"                   = "loopback"
    "myapp/kafka/topicName"        = local.topics.consul.main
    "myapp/kafka/echoTopicName"    = local.topics.consul.echo
    "myapp/kafka/groupId"          = "test-group-one"
    "myapp/kafka/acks"             = "all"
    "myapp/kafka/bootstrapAddress" = "kafka-00:9092,kafka-01:9092,kafka-02:9092"
  }
}

resource consul_key_prefix app_configuration_other {
  path_prefix = "config/k-producer,other/"
  subkeys = {
    "myapp/az"                     = local.availability_zone
    "server/port"                  = "8080"
    "myapp/messenger"              = "kafka"
    "myapp/role"                   = "loopback"
    "myapp/kafka/topicName"        = local.topics.other.main
    "myapp/kafka/echoTopicName"    = local.topics.other.echo
    "myapp/kafka/groupId"          = "test-group-two"
    "myapp/kafka/acks"             = "all"
    "myapp/kafka/bootstrapAddress" = "kafka-00:9092,kafka-01:9092,kafka-02:9092"
  }
}
#resource kafka_topic logs {
#  name               = "test-topic-one"
#  replication_factor = 2
#  partitions         = 1
##  config = {
##    "segment.ms"     = "20000"
##    "cleanup.policy" = "compact"
##  }
#}
