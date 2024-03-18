provider consul {
  address    = "prometheus.podspace.net:443"
  scheme     = "https"
  token      = "d6c38ebc-e53b-7f6b-de62-a40ae9dbf5b7"
  datacenter = "us-east-2"
}

#terraform {
#  required_providers {
#    kafka = {
#      source = "Mongey/kafka"
#    }
#  }
#}
#
#provider kafka {
#  bootstrap_servers = ["18.191.13.75:9092"]
#
#  tls_enabled       = false
#}
