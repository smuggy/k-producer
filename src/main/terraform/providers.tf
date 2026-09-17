# provider consul {
#   address    = "prometheus.podspace.net:443"
#   scheme     = "https"
#   token      = "---put valid token here---"
#   datacenter = "us-east-2"
# }

provider consul {
  address    = "consul.ps.internal:8501"
  scheme     = "https"
  token      = "---put valid token here---"
  datacenter = "local-net-1"
}

# provider consul {
#   address    = "prometheus.podspace.net:443"
#   header {
#     name  = "X-Consul-Prefix"
#     value = "/consul"
#   }
#   scheme     = "https"
#   token = "64d1cb58-9e91-022c-3640-d3d21cd69d13"
#   datacenter = "us-east-2"
# }

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
