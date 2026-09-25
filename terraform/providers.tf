# provider consul {
#   address    = "prometheus.podspace.net:443"
#   scheme     = "https"
#   token      = "---put valid token here---"
#   datacenter = "us-east-2"
# }

# provider consul {
#   address    = "consul.ps.internal:8501"
#   scheme     = "https"
# #  token      = "---put valid token here---"
#   datacenter = "local-net-1"
# }

provider consul {
  address    = "prometheus.podspace.net:443"
  header {
    name  = "X-Consul-Prefix"
    value = "/consul"
  }
  scheme     = "https"
  # token = "----"
  datacenter = "us-east-2"
}

terraform {
  required_providers {
    kafka = {
      source = "Mongey/kafka"
      # Pinned rather than floating: this provider talks the Kafka admin protocol directly, so a
      # version bump can change how topic configuration is diffed. ~> allows patches only.
      version = "~> 0.13"
    }
  }
}

# Connects straight to the brokers - there is no API in front of it - so this only works from a
# host that can reach them. They advertise *.podspace.internal, which resolves inside the VPC only.
provider "kafka" {
  bootstrap_servers = var.kafka_bootstrap_servers

  # The brokers listen PLAINTEXT on 9092. If a TLS or SASL listener is ever added, this and the
  # matching credentials have to change together - and so does myapp.kafka configuration in the
  # application, which currently has no passthrough for security properties.
  tls_enabled = false
}
