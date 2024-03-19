resource consul_key_prefix app_configuration {
  path_prefix = "config/k-producer/myapp/"
  subkeys = {
    "val"                    = "tf-val"
    "messagingMechanism"     = "console"
    "kafka/topicName"        = "test-topic-one"
    "kafka/bootstrapAddress" = "kafka-00:9092,kafka-01:9092,kafka-02:9092"
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
