# Grafana dashboard

`k-producer-dashboard.json` — import via **Dashboards → New → Import → Upload JSON file**, then
pick your Prometheus data source. UID is `k-producer-overview`, so re-importing updates in place
rather than creating a copy.

Every query was built from a live `/actuator/prometheus` scrape of this application rather than
from generic Kafka dashboards, so the series names and labels match what it actually exports.

## Prerequisite: Prometheus must scrape the application

The dashboard is empty until something collects the metrics. The application exposes them at
`/actuator/prometheus`, but nothing scrapes it by default - `deploy/` carries no scrape
annotations, no ServiceMonitor and no PodMonitor, and there is no Prometheus inside the cluster.

`prometheus-scrape.yml` holds a ready scrape job for the external Prometheus at
`prometheus.ps.internal`. Add it to `scrape_configs:` and reload. Confirm it took with:

```shell
curl -s 'http://prometheus.ps.internal:9090/api/v1/targets?state=active' | grep k-producer
curl -s 'http://prometheus.ps.internal:9090/api/v1/label/__name__/values' | grep -c kproducer
```

The second should return a non-zero count. If it returns 0, Prometheus has never successfully
scraped the target and every panel will be blank no matter what the dashboard says.

**Scraping through the ingress only works cleanly at one replica.** The ingress load-balances, so
with two or more pods each scrape lands on an arbitrary one and the per-instance counters appear
to jump backwards. Beyond one replica, scrape the pods directly - Kubernetes service discovery, or
Consul SD once the application registers (it does not today: the deployment sets
`SPRING_PROFILES_ACTIVE=""`, so Consul discovery is off and the catalog holds no `k-producer`
service).

## Rows

| Row | Answers |
|-----|---------|
| Delivery integrity | Did anything get lost, duplicated or reordered? |
| End-to-end latency | How long from publish to consume, and how much is within SLO? |
| Producer | Is the send path healthy — acks, retries, buffer pressure, batching? |
| Consumer | Is the read path keeping up — lag, fetch latency, offset progress? |
| Failure and recovery | What happened during a broker outage, and how long until baseline? |
| Echo relay | Is the return leg forwarding (echo role), and is the probe itself stable? |

## Two things to know before trusting a panel

**1. The `instance` label is renamed on scrape.** The application tags every metric with
`instance`, which collides with the target label Prometheus attaches. With the default
`honor_labels: false`, Prometheus keeps its own and renames the application's to
`exported_instance`. The dashboard therefore filters on `exported_instance`, exposed as the
**Instance label** variable — switch it to `instance` if you scrape with `honor_labels: true`.
Every query interpolates that variable, so it is a single switch rather than an edit across 69
queries.

**2. Latency panels include messages from earlier runs.** The reconciliation counters exclude
foreign messages by run id; the latency timer does not. A consumer resuming from committed
offsets, or running with `autoOffsetReset=earliest`, replays old messages whose timestamps are
hours or days stale, which lands them in the `+Inf` bucket and drags the mean with them. A probe
run against a topic with backlog produced 583 latency samples of which only 25 were from the
current run, with a maximum of ~4 days.

Consequences:

* **Prefer the SLO attainment panel** over the percentile panel when the *Foreign (other runs)*
  stat is non-zero. It is bounded — backlog pushes attainment down rather than off the scale.
* A p99 pinned at `+Inf` almost always means backlog, not a real stall.
* The pollution is permanent for the process lifetime, because a timer's histogram is cumulative.
  Restart the application to get a clean latency baseline.

## Measuring recovery time

The intended sequence when a broker is killed: *assigned partitions* drops → *rebalance activity*
spikes → *connection churn* climbs → lag and in-flight drain back to baseline. Recovery time is
the interval from the drop to lag returning to baseline.

One caveat, called out on the row itself: `assigned partitions` comes from the client's cached
group state. If every broker becomes unreachable the client never learns it lost its partitions,
so that panel can stay flat through a total outage. Corroborate with *Broker request rates* —
requests continuing while responses fall to zero is the unambiguous picture.

## Variables

`role`, `az`, `instance` and `topic` are multi-select with an All default. `role` matters most:
`loopback` measures one-way latency and `origin` measures a round trip, so the two are never
comparable — filter to one before reading any latency panel.
