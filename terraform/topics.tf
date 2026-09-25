# Kafka topics for the probe.
#
# main.tf already names these topics, as Consul configuration the application reads. Naming them
# there while creating them by hand meant the two could drift: change topicName and you get a valid
# configuration pointing at a topic that does not exist. Declaring both here keeps them in step.
#
# It also makes durability a reviewed decision rather than whatever a hand-typed --create happened
# to use. Every topic on this cluster was replication_factor=1 until recently, which makes a
# broker-failure test indistinguishable from data loss - the tool reports the loss correctly, but
# the result says nothing about failover, which is the thing being tested.
#
# Running this needs network reach to the brokers: the provider connects to them directly rather
# than through an API, and they advertise internal-only names. Apply it from inside the VPC.
#   tofu apply -target=kafka_topic.probe

locals {
  # One definition per profile in main.tf, plus its echo counterpart. The keys are the topic names
  # the application is configured with; keeping them in a single map is what stops the two lists
  # diverging silently.
  probe_topics = toset([
    local.topics.ext.main,
    local.topics.ext.echo,
    local.topics.consul.main,
    local.topics.consul.echo,
    local.topics.other.main,
    local.topics.other.echo,
  ])
}

resource "kafka_topic" "probe" {
  for_each = local.probe_topics

  name               = each.value
  partitions         = var.topic_partitions
  replication_factor = var.topic_replication_factor

  config = {
    # Writes are rejected once fewer than this many replicas are in sync. With RF=3 and 2 here, one
    # broker can fail with writes continuing; two failing stops writes rather than silently
    # accepting data that exists on a single node. Meaningful only with acks=all, which the
    # application sets by default - with acks=1 the leader alone acknowledges and this is ignored.
    "min.insync.replicas" = tostring(var.topic_min_insync_replicas)

    # A probe generates continuously and nothing replays it beyond the current run, so a short
    # retention keeps the volume bounded. Raise it if you need to consume a backlog after the fact
    # (autoOffsetReset=earliest), and remember stale messages are excluded from latency by run id
    # but still counted as foreign.
    "retention.ms" = tostring(var.topic_retention_ms)
  }
}
