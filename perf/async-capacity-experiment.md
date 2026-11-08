# Async write capacity

Stage 3 showed the async path holds the sync path's 3,700 events/s. This experiment measures how far above that it goes, chooses `linger.ms` and the persistence consumer count, and gives Stage 4 its ingest baseline.

Measured 2026-11-01 (linger, consumers, single-event ladder, batch ladder to 60,000/s) and 2026-11-08 (batch ladder above 60,000/s and its boundary rounds). Rows are under [`experiments/async-capacity/`](./experiments/async-capacity/).

## Conditions

- One host runs k6, the app, Postgres and Kafka: AMD Ryzen 7 7700, 16 threads. LiveAgent stack down, Prometheus not scraping.
- Pool 10, `events.raw` on 3 partitions. Every run starts from the 20,121,149-row corpus and deletes its own rows afterwards.
- Steady cell: 30 s warm-up, then 180 s measured in six 30 s segments, 1 s acceptance deadline. A rate is `kept up` when every segment's median lag stays under one second of intake, counted in records (a batch request is one record), and under 0.1% of requests go unsent or unaccepted.
- Each session opened with the rig check, `event-counts` p95 within ±5% of its last journalled rows: within 1.2% on 11-01, and +0.2%, +2.3% and +2.7% on 11-08.
- Every row runs the application code of `e8a3fa6`; the stamps `61e2de6` and `d2e873b` are journal-only commits on top of it.

## The three limits

| Limit | Single events | Batches of 100 | What stops it |
|---|---|---|---|
| Acceptance: HTTP → broker ack → `202` | ~35,000 req/s at 100–200 VUs | 226,000 events/s (Stage 3) | the host, 13–14 of 16 cores |
| Persistence: consumers draining a backlog | 98,000 events/s with 3 consumers, 34,500 with one | 37,000–40,000 with one (Stage 3); 3 not measured | the consumers, busy all the time |
| **Sustained: intake held with bounded lag** | **25,000 events/s** | **85,000 events/s** | single: the host; batch: the consumers |

**Single events stop at the machine.** At 30,000/s the host ran 14.9 of 16 cores, 6.6 of them k6's, and k6 could not send 3.8% of the warm-up's requests and 0.3% of the measured window's. The figure belongs to the rig, k6 included, not to the service.

**Batches stop at the three consumers.** At 90,000/s the host used 6.9 of 16 cores and the disk was 30% busy, while every consumer was busy 99.5% of the time and Postgres sessions spent most of it on CPU. Three consumers write through three connections, one transaction at a time each. More throughput needs more consumers, which means more partitions and a connection quota for them.

## Step 1: `linger.ms` = 5

`linger.ms` is how long the producer waits to gather records into one broker request. It was never set; Kafka 4.0 moved the library default from 0 to 5.

Steady 3,700/s, one consumer:

| `linger.ms` | `202` p99 | consumer busy | disk busy |
|---|---|---|---|
| 0 | 1.2 ms | 0.90 | 0.65 |
| 1 | 2.1 ms | 0.90 | 0.65 |
| 5 | 6.0 ms | 0.42 | 0.24 |

Closed-model single events (`write/load/single`), req/s:

| VUs | linger 0 | linger 5 |
|---|---|---|
| 10 (Stage 3) | 18,122 (one scratch round) | 1,749 |
| 50 | 30,147 | 8,361 |
| 100 | 33,588 | 33,442 |
| 200 | 35,357 | 36,115 |

Chosen 5, set explicitly:
- 6 ms to the `202` costs an analytics write nothing;
- at 3,700/s the consumer works half as hard and the disk a third as hard, headroom for the reads that share them;
- the acceptance limit does not move; the host bounds it;
- explicit, so a library upgrade cannot change it again.

The cost falls on clients with few connections in flight: 50 reach 8,400 req/s instead of 30,000. Such clients should send batches.

## Step 2: three consumers

Restart cell at 3,700/s with a 300 s outage, ~1.12M records of backlog, linger 5:

| Consumers | Rejoin | Drain rate | Consumer busy | Host | Postgres | Disk busy |
|---|---|---|---|---|---|---|
| 1 | ~3 s | 34,500/s | 0.97 | 2.9 cores | 0.75 cores | 0.11 |
| 3 | ~6 s | 98,000/s | 0.91 each | 4.8 cores | 2.1 cores | 0.25 |

Figures are over the drain plateau. The row's `drain_after_restart_seconds` (39.7 vs 19.2) and `drain_saturation` include the rejoin, so they show ×2 where the plateau shows ×2.85.

At 3,700/s three consumers cost no more than one: busy 0.14 each against 0.42, disk 0.24 in both rows.

Chosen 3: one per partition. A fourth would get no partition, and two would split three unevenly. The cost: during a drain the consumers hold 3 of the pool's 10 connections. What that does to reads is for the mixed cell's re-measure.

## Step 3: the sustained limit

One round per rate to find the boundary, then three rounds at it.

Single events:

| Rate/s | Verdict | Persisted/s | Highest segment lag (bound) | p99 | Host | k6 | App | Consumer busy |
|---|---|---|---|---|---|---|---|---|
| 3,700 | kept up | 3,700 | 21 (3,700) | 5.9 ms | 2.3 | 0.9 | 1.1 | 0.14 |
| 7,500 | kept up | 7,461 | 43 (7,500) | 7.0 ms | 4.0 | 1.7 | 1.9 | 0.19 |
| 15,000 | kept up | 14,841 | 106 (15,000) | 13.3 ms | 6.9 | 2.9 | 3.2 | 0.46 |
| 20,000 | kept up | 19,680 | 159 (20,000) | 35.3 ms | 9.9 | 4.2 | 4.5 | 0.67 |
| 25,000 ×4 | kept up ×4 | 24,592–24,618 | 423–626 (25,000) | 66–69 ms | 12.8 | 5.5 | 5.8 | 0.79–0.80 |
| 30,000 | FELL BEHIND | 29,409 | 34,223 (30,000) | 136 ms | 14.9 | 6.6 | 6.8 | 0.90 |

Batches of 100:

| Date | Rate/s | Verdict | Persisted/s | Highest segment lag (bound) | p99 | Host | Postgres | Consumer busy | Disk busy |
|---|---|---|---|---|---|---|---|---|---|
| 11-01 | 15,000 | kept up | 15,000 | 1 (150) | 6.5 ms | 1.2 | 0.4 | 0.21 | 0.19 |
| 11-01 | 30,000 | kept up | 30,000 | 2 (300) | 6.0 ms | 2.4 | 0.8 | 0.48 | 0.39 |
| 11-01 | 60,000 | kept up | 59,999 | 15 (600) | 6.3 ms | 4.5 | 1.5 | 0.96 | 0.74 |
| 11-08 | 60,000 | kept up | 59,999 | 7 (600) | 6.4 ms | 4.7 | 1.5 | 0.88 | 0.56 |
| 11-08 | 75,000 | kept up | 74,996 | 17 (750) | 7.0 ms | 5.7 | 1.9 | 0.95 | 0.51 |
| 11-08 | 80,000 | kept up | 79,991 | 44 (800) | 8.0 ms | 6.0 | 2.0 | 0.98 | 0.51 |
| 11-08 | 85,000 ×4 | kept up ×4 | 84,859–85,004 | 35–136 (850) | 7.9–8.7 ms | 6.4–6.6 | 2.1–2.2 | 0.97–0.99 | 0.36–0.45 |
| 11-08 | 90,000 | FELL BEHIND | 89,327 | 1,867 (900) | 10.2 ms | 6.9 | 2.2 | 0.995 | 0.30 |

At 90,000/s lag climbed for two minutes, then held near 1,800 records, twice the bound, for the last 90 s: the edge of what three consumers hold.

## Caveats

- **The 30,000/s single verdict reads `FELL BEHIND` but means the rig.** Only the first segment broke the bound; later ones held near 5,000. The cell checks lag before the offered rate, so k6's 0.3% unsent requests, three times the bound, did not surface as `RATE NOT OFFERED`. Either way 30,000/s is not held on this machine.
- **`measure_persisted_events_per_sec` reads low at high request counts.** `runWindow` in `perf/lib/k6-summary.js` dates the run from when `handleSummary` runs, which aggregating millions of samples delays by ~3 s at 25,000 req/s. The window then includes idle seconds after the stream: 24,600 for a true 25,000 (−1.5%). Verdicts are unaffected, and batch rows (at most 900 req/s) carry under 0.2%. The fix waits until after the experiment so its rows stay comparable.
- **Sustained means 3.5 minutes on a growing table.** At 85,000/s a run adds ~18M rows to the 20M corpus before the cleanup deletes them. Hours at this rate are unmeasured, and the index a soak would build is much larger. Stage 4 comparisons need the same conditions.
- **Index free space grew between sessions**, from 4,563 MB to 4,911 and then 5,722 MB, as runs inserted and deleted millions of rows. The 60,000/s step repeated on 11-08 kept up with less consumer work than on 11-01 (busy 0.88 vs 0.96).

## What it gives

- **Ingest baseline for Stage 4**, on this rig: 25,000 single events/s, bound by the machine, and 85,000 events/s in batches of 100, bound by three consumers.
- **Regression floor** at ~80% of it: ~20,000/s single and ~68,000/s batch, guarded by steady cells at those rates.
- **Raising the batch limit is a consumer-count question**: more partitions, which cannot be reduced again, and a connection quota so the consumers cannot starve reads. It belongs with Stage 4's freshness work, not to tuning.

## Reproduce

```bash
scripts/actions/perf/app --spring.kafka.producer.properties.linger.ms=5 --spring.kafka.listener.concurrency=3
LADDER_RATES="3700 7500 15000 20000 25000 30000" scripts/actions/perf/write/steady/ladder
BATCH_SIZE=100 LADDER_RATES="60000 75000 80000 85000 90000" scripts/actions/perf/write/steady/ladder
BATCH_SIZE=100 LADDER_RATES=85000 ROUNDS=3 scripts/actions/perf/write/steady/ladder
```

The consumer comparison runs `scripts/actions/perf/write/restart/single` with `RATE=3700 DOWNTIME_SECONDS=300 DRAIN_BUDGET_SECONDS=120 AFTER_SECONDS=150` and `JOURNAL` pointed at `experiments/async-capacity/concurrency/restart-single/`, the app launched with `--spring.kafka.listener.concurrency=1` or `3`.
