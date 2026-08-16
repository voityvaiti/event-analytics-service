# Read spike — surviving a burst of dashboard traffic

Steps the request rate far above what the connection pool can serve, holds it,
then drops back and checks the read path serves cleanly again. One cell per
endpoint, all of them running [`stats-spike.js`](./stats-spike.js) through
[`measure-cell.sh`](./measure-cell.sh) — a cell's own README covers only what its
query brings to that.

## Why one cell per endpoint

A spike measures what happens when requests outrun the pool: queueing, shedding,
and whether the queue drains once the surge passes. That is a property of the
pool and the queue rather than of a query shape, and on that reasoning
`active-users` was the only cell here for a while — the heaviest read, so the
first to reach the ceiling.

The [index experiment](../../index-experiment.md) took the reasoning apart. Per-query
cost spreads 11x across the three endpoints at 20M rows, and the ceiling a surge
has to clear is pool size over query latency, so the endpoints reach it at rates
an order of magnitude apart. One can drain where another cannot — three
questions, not the same question three times.

## Each cell surges at ~5x its own ceiling

A single absolute rate cannot mean the same thing at three ceilings an order of
magnitude apart. The 400 req/s that overwhelms `active-users` five times over is
less than half of what `event-counts` serves comfortably: shared, it would have
bought a cell that never surges and always recovers.

| Cell | Query | 1d `p95` | Ceiling | `SPIKE_RATE` |
|---|---|---|---|---|
| [`event-counts/`](./event-counts) | `groupBy=type` | 11.9 ms | ~840 req/s | 4000 |
| [`top-pages/`](./top-pages) | `limit=10` | 32.6 ms | ~310 req/s | 1500 |
| [`active-users/`](./active-users) | `groupBy=day` | 126 ms | ~79 req/s | 400 |

The ceiling is the pool (10) over that endpoint's 1d-window `p95`, read off its
own load journal at 20M rows with the index — the arithmetic the index experiment
checked against measured surges and found within 2.7% and 6.1% at this density.
The rates are rounded, so they are 4.8x, 4.9x and 5.1x rather than exactly five;
`active-users` keeps the 400 it always ran at, which was already ~5x, so its
series is unbroken and the two new cells adopt the rule rather than the number.

Those figures are frozen to the reference rig, and a pool change, a corpus change
or a faster plan moves every one of them. The row says when that has happened:
**`spike_achieved_rps` back at about `spike_rate` with `spike_dropped` 0 means
nothing was outrun** — re-derive the rate from the current load journal instead
of reading the run as a pass. Rows written before `stats-spike.js` counted rates
per phase carry a `spike_achieved_rps` diluted across the whole run and cannot be
read against that rule; their `commit` stamp is what separates them.

## What the first rows measured

Medians of three rounds each, at 20M rows with the index:

| Cell | Baseline `p95` | Sustained | Predicted ceiling | Recovery `p95` | Verdict |
|---|---|---|---|---|---|
| `event-counts` | 14.4 ms | 717 req/s | ~840 | 14.4 ms (1.0x) | recovered |
| `top-pages` | 30.9 ms | 284 req/s | ~310 | 447 ms (14.5x) | STILL DRAINING |
| `active-users` | 124 ms | 84 req/s | ~79 | 6.56 s (53x) | STILL DRAINING |

The ceilings were derived, not fitted, and every cell sustained within 15% of
its own — so the rate rule above rests on a measurement rather than on the
arithmetic alone. What the three rows say together is that surviving a burst is
decided by what a query costs, not by how much traffic arrives: the same 5x
overload is absorbed whole at 14ms, leaves a half-second tail at 31ms, and a
six-second one at 124ms.

`top-pages` is the cell to watch. It is the one sitting closest to its own
baseline, so it is the first that would turn green when heavy-query protection
lands — a better early gate candidate than `active-users`, which is 53x away.

## What is deliberately equal across the cells

Only the query and the rate differ. Three things that could have been tuned per
cell are not, because they are what makes the cells comparable:

- **`BASELINE_RATE` 20 req/s** — at most a quarter of the lowest ceiling and a
  fortieth of the highest, so it is a calm rate everywhere, and calm is all it
  has to be: it exists to establish what the endpoint costs unqueued, which is
  the reference the recovery verdict is read against.
- **`MAX_VUS` 500** — the client's cap on requests in flight. Once the app is
  over its ceiling k6 cannot hold the offered rate and sheds the remainder as
  `dropped_iterations`, so that figure is client-side by construction in every
  over-ceiling run: the 20M `active-users` row served 2535 and dropped 9459 of
  the 12000 it offered. Raising the cap per cell would not change what the number
  means, only how deep a queue each surge leaves behind — and that queue is
  precisely what `recovery_p95_ms` measures. Equal depth, comparable recoveries.
- **The 1d window** — the load cells mix window sizes because real traffic does;
  these pin one. Mixing them would change how much work each request costs at the
  same time as the request rate, and a spike exists to change one of those. The
  position still moves, so no run is answered from one cached stretch.

The warm-up is the exception, and it is one by design: it runs once per process,
on whichever cell goes first, so in a multi-cell run the later cells surge a path
warmed by somebody else's query. It costs nothing worth spending, because a cold
start would surface in a cell's `baseline` phase — before the surge and inside the
reference the verdict is read against — rather than in the surge itself.

## Reading a row

Each cell's `journal.jsonl` is its own series, written only by that cell's task,
never by hand, and never merged with the load journals or with another cell's — a
different question, measured differently. The run has three phases: `baseline`
establishes what a calm rate costs, `spike` is the surge, and `recovery` returns
to the calm rate to ask whether the surge is over.

The question is recovery. The surge itself is allowed to shed, and
`spike_dropped` records how much k6 could not even hand over. What must hold is
`recovery_failed_rate` near zero with `recovery_p95_ms` back near
`baseline_p95_ms` — **and** `baseline_p95_ms` under `BASELINE_MAX_P95_MS`
(1000ms), because a baseline that is itself saturated is not a reference. Without
that precondition the ratio compares broken against broken and passes: in the
index experiment's no-index arm at the default corpus, a 29.6s recovery sat only
1.8x above a baseline that had itself collapsed to 16.7s, which clears the 5x
margin comfortably — while the indexed arm's 6.3s recovery against a healthy 124ms
baseline does not.

`recovered` carries the verdict, so a row answers its own question instead of
leaving a reader to apply the rule by hand. It is derived from the three fields
above, and which version of the rule produced it is answered by the row's
`commit` — the same stamp every other field is read against. Rows written before
the baseline precondition existed carry whatever the older rule concluded; their
`commit` is what makes that difference legible rather than confusing.

`index_scans` and `seq_scans` are carried here for the same reason as in the load
cells: without the index a surge would be answered by sequential scans, and the
row should say so rather than leave a collapse unexplained.

## Why none of them gate

Nothing in the app cuts off a long-running read yet, so a surge past the pool's
ceiling always leaves a tail and the verdict would be red on every run — and a
permanently red test stops being read. These cells journal and print instead, and
become gates once a statement timeout or a queue limit lands. The write spike does
gate: it genuinely recovers.

The tail is a function of what a query costs, not a property of the surge. At a
tenth of the corpus the index experiment's indexed arm absorbed the whole 400
req/s step with recovery back at its 13ms baseline — a clean `recovered` from the
`active-users` cell. At the default corpus it does not, with or without the index,
which is what makes that protection the thing that would change the verdict.
