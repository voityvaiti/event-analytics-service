#!/bin/bash

# Put the measured windows a journal records onto the Grafana dashboard, one
# annotation per row, so a run is found and opened there rather than copied out
# of a JSON file by hand.
#
# Two callers, one entry point: the harness at the end of a run (perf_report),
# and scripts/actions/observability when the stack comes up. The second is what
# makes a run measured while Grafana was down appear the moment it is started,
# and it only works because both do the same idempotent thing — read back what
# Grafana already holds, skip it, create the rest. Neither caller has to know
# what the other did.
#
# Rows written before the window stamps existed carry no `started_at`. They are
# counted and never annotated: a region invented for them would be a claim about
# a window nobody measured, which is the defect the stamps removed. A line that
# cannot be read at all is counted the same way — the realistic one is a journal
# a pass is appending to while this runs, and half a line is worth one missing
# region, not every other journal's runs as well.
#
# An annotation is written once and never revisited, so a journal whose figures
# are rewritten later — perf journals here get rebased — keeps the text its
# annotation was first given. What a reader navigates by is the window, and that
# is the one thing a rewrite of the figures does not move.
#
# Grafana being down is the ordinary case, the same way a stopped stack is for
# read_scrape — it prints one line and exits 0. A dashboard is never worth a
# failed measurement, so nothing here aborts on a failed request; each one is
# checked where it is made rather than through `set -e`.
#
# Usage: perf/lib/annotate-runs.sh <journal.jsonl>...

set -uo pipefail

# Where the compose file publishes Grafana. Overridable for the same reason
# BASE_URL and PROMETHEUS_URL are: a stack somewhere else is a URL, not a fork.
GRAFANA_URL=${GRAFANA_URL:-http://localhost:3000}

# The tag every annotation from here carries, and the only one the dashboard's
# annotation query and its run list filter on. Everything else a tag says —
# which cell, which commit, whether the meters and the scrape were on — is there
# to narrow a list that this one gathers.
RUN_TAG=perf-run

# Grafana's own default page size for GET /api/annotations, and the size this
# reads back in. A single large limit would work until the journals outgrew it,
# and the first symptom would be the oldest runs being created a second time.
PAGE_SIZE=100

if [ "$#" -eq 0 ]; then
  echo "Usage: $0 <journal.jsonl>..." >&2
  exit 2
fi

# One annotation per measured window, as "<key>\t<time>\t<timeEnd>\t<body>".
#
# The key is what makes this idempotent, and it holds everything that tells two
# regions apart: the cell, the run, the phase within it, and the instant the
# region begins. `run_id` alone reads as enough, since k6 mints one per run — but
# only when nothing hands it one, and an operator with RUN_ID exported gives every
# cell of a pass the same id. A key without the cell and the window would then
# collapse that whole pass into a single annotation, last row read winning. Each
# part of it is read back off the annotation's own tags and `time`, so both sides
# build the same key out of the same facts.
#
# Nothing here knows a cell by name. The tag comes from the journal's directory,
# which is a cell's identity, and the text is assembled from whichever known
# fields the row happens to carry — so a new cell is legible without being known.
plan=$(python3 - "$RUN_TAG" "$@" <<'PY'
import json, os, sys
from datetime import datetime

run_tag, journal_paths = sys.argv[1], list(dict.fromkeys(sys.argv[2:]))

# What tells two rows of one cell apart, in front of the figures.
QUALIFIERS = (
    ("group_by", "groupBy={}".format),
    ("window", "window={}".format),
    ("limit", "limit={}".format),
    ("batch_size", "batch {}".format),
)

# The figures worth recognising a run by, in the order the run digest reads
# them. Matched by name against what a row has rather than branched on which
# cell wrote it.
HEADLINE = (
    ("throughput_rps", "{} req/s".format),
    ("achieved_rps", "{} req/s".format),
    ("events_per_sec", "{} events/s".format),
    ("achieved_events_per_sec", "{} events/s".format),
    ("dropped", "dropped {}".format),
    ("med_ms", "med {}ms".format),
    ("p95_ms", "p95 {}ms".format),
    ("p99_ms", "p99 {}ms".format),
    ("max_ms", "max {}ms".format),
    ("failed_rate", lambda value: "%.2f%% failed" % (value * 100)),
    ("index_scans", "{} index scans".format),
    ("seq_scans", "{} seq scans".format),
    ("recovered", lambda value: "recovered" if value else "DID NOT RECOVER"),
)

# The stamps that say which app produced a number and what else it was doing,
# carried as tags so a dashboard can filter a list down to one arm of an
# experiment.
STAMPS = ("commit", "request_metrics", "scrape")

SUFFIX = "_started_at"


def cell_of(journal_path):
    """The cell that owns a journal, which is its directory below perf/."""
    parts = os.path.dirname(os.path.abspath(journal_path)).split(os.sep)
    below_perf = len(parts) - parts[::-1].index("perf") if "perf" in parts else len(parts) - 1
    return "/".join(parts[below_perf:])


def epoch_millis(stamp):
    """A row's UTC window stamp as Grafana's epoch milliseconds.

    The offset is read, never assumed: a stamp parsed as naive would be taken
    for rig-local time and land the region hours away from the run, on a
    dashboard that still draws a plausible-looking band.
    """
    parsed = datetime.fromisoformat(stamp.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("window stamp %r names no timezone" % stamp)
    return round(parsed.timestamp() * 1000)


def phases_of(row):
    """The phases a row bounds separately, in the order they ran.

    Found by their bounds rather than by a list of names, so a run that grows a
    fourth phase is drawn without being described here twice.
    """
    bounded = [
        name[: -len(SUFFIX)]
        for name in row
        if name.endswith(SUFFIX) and name[: -len(SUFFIX)] + "_finished_at" in row
    ]
    return sorted(bounded, key=lambda phase: row[phase + SUFFIX])


def figures_of(row, phase, phases):
    """A region's figures: the phase's own, over those belonging to the run.

    A phase prefixes its fields (`spike_p95_ms`), so stripping that prefix puts
    them under the same names a row without phases uses and one table reads
    both. What is left unprefixed — a spike's verdict, the scans a read cell
    counted — describes the whole run, and every one of its regions is part of
    that run.
    """
    owned = tuple(name + "_" for name in phases)
    figures = {name: value for name, value in row.items() if not name.startswith(owned)}
    if phase:
        figures.update(
            {
                name[len(phase) + 1:]: value
                for name, value in row.items()
                if name.startswith(phase + "_")
            }
        )
    return figures


def text_of(row, cell, phase, figures):
    title = " ".join(
        [cell]
        + ([phase] if phase else [])
        + [render(row[name]) for name, render in QUALIFIERS if row.get(name) not in (None, "")]
    )
    stated = [
        render(figures[name])
        for name, render in HEADLINE
        if isinstance(figures.get(name), (int, float, bool))
    ]
    return title + ": " + " | ".join(stated) if stated else title


planned = {}
skipped = 0
unreadable = 0

for journal_path in journal_paths:
    cell = cell_of(journal_path)
    with open(journal_path) as journal:
        for line in journal:
            if not line.strip():
                continue

            try:
                row = json.loads(line)

                phases = phases_of(row)
                regions = [
                    (phase, row[phase + SUFFIX], row[phase + "_finished_at"]) for phase in phases
                ]
                if not regions and row.get("started_at") and row.get("finished_at"):
                    regions = [("", row["started_at"], row["finished_at"])]

                run_id = row.get("run_id")
                if not regions or not run_id:
                    skipped += 1
                    continue

                planned_for_row = {}
                for phase, started_at, finished_at in regions:
                    tags = [run_tag, "cell:" + cell]
                    if phase:
                        tags.append("phase:" + phase)
                    tags += [
                        "%s:%s" % (name, row[name]) for name in STAMPS if row.get(name) is not None
                    ]
                    tags.append("run:" + str(run_id))

                    started, finished = epoch_millis(started_at), epoch_millis(finished_at)
                    planned_for_row["%s/%s/%s/%d" % (cell, run_id, phase, started)] = (
                        started,
                        finished,
                        {
                            "time": started,
                            "timeEnd": finished,
                            "tags": tags,
                            "text": text_of(row, cell, phase, figures_of(row, phase, phases)),
                        },
                    )
            except Exception:
                unreadable += 1
                continue

            planned.update(planned_for_row)

for key, (started, finished, body) in planned.items():
    print("%s\t%d\t%d\t%s" % (key, started, finished, json.dumps(body)))

if skipped:
    print(
        "%d journal row(s) carry no measured window and were left off the dashboard." % skipped,
        file=sys.stderr,
    )

if unreadable:
    print(
        "%d journal line(s) could not be read and were left off the dashboard." % unreadable,
        file=sys.stderr,
    )
PY
) || {
  echo "Could not read the journals — nothing annotated." >&2
  exit 1
}

if [ -z "$plan" ]; then
  echo "No measured windows to put on the dashboard."
  exit 0
fi

if ! curl -sf -m 5 -o /dev/null "$GRAFANA_URL/api/health"; then
  echo "Grafana is not up at $GRAFANA_URL — the journals stay the record until it is."
  exit 0
fi

# The window the journals cover, so the read-back below asks about the runs it is
# about to offer and nothing else.
range_from=$(printf '%s\n' "$plan" | cut -f2 | sort -n | head -1)
range_to=$(printf '%s\n' "$plan" | cut -f3 | sort -n | tail -1)

# What Grafana already holds, keyed the way the plan is. Read a page at a time,
# each page ending where the previous one's oldest annotation began, because the
# API answers with a bounded number of the most recent and would otherwise leave
# the oldest runs looking absent.
#
# Paging turns on the cursor alone, never on what a page happened to contain: a
# page shorter than the limit is the last one, and a boundary that does not move
# strictly backwards would ask for the page just read again (`to` is inclusive).
# A full page of `perf-run` annotations that this did not write — hand-made, or
# older than the `run:` tag — adds nothing to the map and still has every older
# page behind it.
declare -A present=()
boundary=$range_to
while :; do
  page=$(curl -sf -m 10 --get "$GRAFANA_URL/api/annotations" \
    --data-urlencode "tags=$RUN_TAG" \
    --data-urlencode "type=annotation" \
    --data-urlencode "from=$range_from" \
    --data-urlencode "to=$boundary" \
    --data-urlencode "limit=$PAGE_SIZE") || {
    echo "Could not read the annotations Grafana holds — nothing created, to avoid duplicating them." >&2
    exit 0
  }

  parsed=$(printf '%s' "$page" | python3 -c '
import json, sys

limit = int(sys.argv[1])
annotations = json.load(sys.stdin)
oldest = None

for annotation in annotations:
    oldest = annotation["time"] if oldest is None else min(oldest, annotation["time"])
    tagged = dict(tag.split(":", 1) for tag in annotation["tags"] if ":" in tag)
    if "run" in tagged:
        print(
            "%s/%s/%s/%d"
            % (
                tagged.get("cell", ""),
                tagged["run"],
                tagged.get("phase", ""),
                annotation["time"],
            )
        )

# Only a full page can be hiding older ones; anything shorter is the end of them.
if oldest is not None and len(annotations) >= limit:
    print("NEXT %d" % oldest)
' "$PAGE_SIZE") || {
    echo "Could not read the annotations Grafana holds — nothing created, to avoid duplicating them." >&2
    exit 0
  }

  next_boundary=""
  while IFS= read -r line; do
    case "$line" in
      "NEXT "*) next_boundary=${line#NEXT } ;;
      "") ;;
      *) present[$line]=1 ;;
    esac
  done <<< "$parsed"

  [ -n "$next_boundary" ] && [ "$next_boundary" -lt "$boundary" ] || break
  boundary=$next_boundary
done

created=0
already=0
while IFS=$'\t' read -r key _ _ body; do
  if [ -n "${present[$key]:-}" ]; then
    already=$((already + 1))
    continue
  fi
  curl -sf -m 10 -o /dev/null -X POST \
    -H 'Content-Type: application/json' \
    --data-binary "$body" "$GRAFANA_URL/api/annotations" || {
    echo "Grafana stopped accepting annotations after $created — the rest wait for the next sync." >&2
    break
  }
  created=$((created + 1))
done <<< "$plan"

echo "Dashboard: $created run annotation(s) created, $already already there ($GRAFANA_URL)."
