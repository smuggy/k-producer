# Need to specify export SPRING_PROFILES_ACTIVE="consul,ext"
resource consul_key_prefix app_configuration_ext {
  path_prefix = "config/k-producer,ext/"
  subkeys = {
    "server/port"                  = "8090"
    "myapp/val"                    = "tf-val"
    "myapp/messenger"              = "kafka"
    "myapp/kafka/topicName"        = "test-topic-one"
    "myapp/kafka/groupId"          = "test-group-one"
    "myapp/kafka/bootstrapAddress" = "kafka-00:9092,kafka-01:9092,kafka-02:9092"
  }
}

resource consul_key_prefix app_configuration_other {
  path_prefix = "config/k-producer,other/"
  subkeys = {
    "server/port"                  = "8080"
    "myapp/val"                    = "tf-other-val"
    "myapp/messenger"              = "kafka"
    "myapp/kafka/topicName"        = "test-topic-two"
    "myapp/kafka/groupId"          = "test-group-two"
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
