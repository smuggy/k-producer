resource consul_key_prefix app_configuration {
  path_prefix = "config/k-producer/myapp/"
  subkeys = {
    "val"                    = "tf-val"
    "kafka/topicName"        = "test-topic-one"
    "kafka/bootstrapAddress" = "kafka-00:9092,kafka-01:9092,kafka-02:9092"
  }
}
