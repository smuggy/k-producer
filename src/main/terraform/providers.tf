provider consul {
  address    = "prometheus.podspace.net:443"
  scheme     = "https"
  token      = ""
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
