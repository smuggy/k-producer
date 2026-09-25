variable "kafka_bootstrap_servers" {
  description = <<-EOT
    Brokers the Kafka provider connects to when managing topics. These must be reachable from
    wherever Terraform runs - the provider talks to the brokers directly, and they advertise
    internal-only names, so this generally means running from inside the VPC.
  EOT
  type        = list(string)
  default = [
    "kafka-00.podspace.internal:9092",
    "kafka-01.podspace.internal:9092",
    "kafka-02.podspace.internal:9092"
  ]
}

variable "topic_partitions" {
  description = <<-EOT
    Partitions per topic. More than one is what makes partition behaviour observable at all:
    Kafka orders within a partition only, so a multi-partition topic reports reordering as a
    matter of course. Pair with myapp.publisher.keyCount to place messages deliberately.
  EOT
  type        = number
  default     = 3
}

variable "topic_replication_factor" {
  description = <<-EOT
    Replicas per partition. Must not exceed the broker count. This is the setting that decides
    whether a broker-failure test measures failover or just measures data loss - at 1 there is
    nothing to fail over to.
  EOT
  type        = number
  default     = 3

  validation {
    condition     = var.topic_replication_factor >= 1
    error_message = "replication_factor must be at least 1."
  }
}

variable "topic_min_insync_replicas" {
  description = <<-EOT
    Replicas that must acknowledge before a write succeeds, with acks=all. Must be less than the
    replication factor, or a single broker failure stops writes entirely.
  EOT
  type        = number
  default     = 2

  validation {
    condition     = var.topic_min_insync_replicas >= 1
    error_message = "min.insync.replicas must be at least 1."
  }
}

variable "topic_retention_ms" {
  description = "How long messages are kept. Default 6 hours - a probe's output has no value beyond the run."
  type        = number
  default     = 21600000
}

# Cross-variable validation. Terraform cannot express this inside a single variable block, so it
# lives in a check: min.insync.replicas >= replication_factor means every write needs every replica
# and the loss of any one broker halts production - the opposite of what replication is for.
check "insync_below_replication" {
  assert {
    condition = var.topic_min_insync_replicas < var.topic_replication_factor
    error_message = format(
      "min.insync.replicas (%d) must be below replication_factor (%d); otherwise losing one broker stops all writes.",
      var.topic_min_insync_replicas, var.topic_replication_factor
    )
  }
}
