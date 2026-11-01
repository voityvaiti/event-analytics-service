"""Samples what each part of the rig is doing once a second, and reads the samples back.

Run as `saturation_samples.py <file> <base_url> <app_pid> <shell_pid> <k6_label>` it appends
one JSON line a second until it is terminated or the shell that started it exits. Every
counter is cumulative, so a rate is always the difference between two samples over the
time between them and a late tick cannot distort it. A part that could not be read is
left out of its sample, never written as 0.

Containers are read from their cgroup and the app and host from /proc: reading a file
costs the rig nothing, where `docker stats` would keep dockerd busy. The consumer's busy
share comes from Spring's `spring.kafka.listener` timer, because the listener runs on a
virtual thread with no CPU counter of its own: a share near 1 means the consumer spent
the whole second processing, on CPU or waiting for Postgres. Postgres reports its
sessions through one psql session held open for the whole run.

Cells import load, summarize and curve from their journalling heredocs.
"""

import json
import os
import queue
import resource
import signal
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request

CLOCK_TICKS = os.sysconf("SC_CLK_TCK")
COMPOSE_SERVICES = ("postgres", "kafka")
SESSIONS_QUERY = (
    "SELECT CASE WHEN state = 'active' THEN coalesce(wait_event_type, 'CPU') ELSE state END,"
    " count(*) FROM pg_stat_activity"
    " WHERE datname = current_database() AND pid <> pg_backend_pid()"
    " AND state IS NOT NULL AND state <> 'idle' GROUP BY 1;"
)
ANSWER_END = "__answer_end__"
READ_FAILURES = (OSError, ValueError, KeyError, IndexError, StopIteration, queue.Empty,
                 subprocess.SubprocessError)


def _command(arguments):
    return subprocess.run(arguments, capture_output=True, text=True, timeout=5).stdout.strip()


def _cgroup_of(container_id):
    if not container_id:
        return None
    pid = _command(["docker", "inspect", "-f", "{{.State.Pid}}", container_id])
    with open("/proc/%s/cgroup" % pid) as f:
        relative = next(line.split("::", 1)[1].strip() for line in f if line.startswith("0::"))
    return "/sys/fs/cgroup" + relative


def _read_cgroup(path):
    with open(path + "/cpu.stat") as f:
        cpu_usec = next(int(line.split()[1]) for line in f if line.startswith("usage_usec "))
    read_bytes = written_bytes = 0
    with open(path + "/io.stat") as f:
        for line in f:
            fields = dict(field.split("=", 1) for field in line.split()[1:])
            read_bytes += int(fields.get("rbytes", 0))
            written_bytes += int(fields.get("wbytes", 0))
    return {"cpu_seconds": cpu_usec / 1e6, "read_bytes": read_bytes, "written_bytes": written_bytes}


def _disk_of(path):
    device = os.stat(path).st_dev
    block = os.path.realpath("/sys/dev/block/%d:%d" % (os.major(device), os.minor(device)))
    if os.path.exists(block + "/partition"):
        block = os.path.dirname(block)
    return os.path.basename(block)


def _read_disk_busy_ms(disk):
    with open("/proc/diskstats") as f:
        return next(int(fields[12]) for fields in map(str.split, f) if fields[2] == disk)


def _read_host_ticks():
    with open("/proc/stat") as f:
        ticks = [int(value) for value in f.readline().split()[1:9]]
    idle = ticks[3] + ticks[4]
    return [sum(ticks) - idle, sum(ticks)]


def _read_process_cpu_seconds(pid):
    with open("/proc/%s/stat" % pid) as f:
        fields = f.read().rsplit(")", 1)[1].split()
    return (int(fields[11]) + int(fields[12])) / CLOCK_TICKS


class _Actuator:
    def __init__(self, base_url):
        self.base_url = base_url

    def read(self, name, tag=None):
        url = self.base_url + "/actuator/metrics/" + name
        if tag:
            url += "?tag=" + urllib.parse.quote(tag)
        with urllib.request.urlopen(url, timeout=0.5) as response:
            body = json.load(response)
        values = {m["statistic"]: m["value"] for m in body["measurements"]}
        tags = {t["tag"]: t["values"] for t in body.get("availableTags", [])}
        return values, tags

    def listener_seconds(self):
        _, tags = self.read("spring.kafka.listener")
        return {
            name: self.read("spring.kafka.listener", "name:" + name)[0]["TOTAL_TIME"]
            for name in tags.get("name", [])
        }

    def pool(self):
        acquire, _ = self.read("hikaricp.connections.acquire")
        return {
            "active": self.read("hikaricp.connections.active")[0]["VALUE"],
            "pending": self.read("hikaricp.connections.pending")[0]["VALUE"],
            "acquire_count": acquire["COUNT"],
            "acquire_seconds": acquire["TOTAL_TIME"],
        }


class _Sessions:
    """One psql session that counts Postgres sessions by what they are doing, on request.

    Each answer ends with a numbered marker, so an answer that arrives too late for its
    tick is discarded instead of being read as the next tick's.
    """

    def __init__(self):
        self.process = subprocess.Popen(
            ["docker", "compose", "exec", "-T", "postgres",
             "psql", "-U", "event_analytics", "-d", "event_analytics", "-AtX", "-F", "\t"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            text=True, bufsize=1,
        )
        self.lines = queue.Queue()
        self.asked = 0
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        for line in self.process.stdout:
            self.lines.put(line.rstrip("\n"))

    def count(self):
        self.asked += 1
        self.process.stdin.write("%s\n\\echo %s %d\n" % (SESSIONS_QUERY, ANSWER_END, self.asked))
        self.process.stdin.flush()
        deadline = time.monotonic() + 0.5
        counts = {}
        while True:
            line = self.lines.get(timeout=max(0.0, deadline - time.monotonic()))
            if line.startswith(ANSWER_END):
                if int(line.split()[1]) == self.asked:
                    return counts
                counts = {}
            else:
                state, sessions = line.split("\t")
                counts[state] = int(sessions)

    def close(self):
        self.process.terminate()


def _attempt(read):
    try:
        return read()
    except READ_FAILURES:
        return None


def _sample(app_pid, actuator, sessions, containers, find_container, disk):
    usage = [resource.getrusage(who) for who in (resource.RUSAGE_SELF, resource.RUSAGE_CHILDREN)]
    sample = {
        "at": round(time.time() * 1000),
        "cpus": os.cpu_count(),
        "host_ticks": _attempt(_read_host_ticks),
        "app_cpu_seconds": _attempt(lambda: _read_process_cpu_seconds(app_pid)),
        "disk_busy_ms": _attempt(lambda: _read_disk_busy_ms(disk)) if disk else None,
        "listener_seconds": _attempt(actuator.listener_seconds),
        "pool": _attempt(actuator.pool),
        "sessions": _attempt(sessions.count) if sessions else None,
        "sampler_cpu_seconds": sum(u.ru_utime + u.ru_stime for u in usage),
    }
    for name in list(find_container):
        if containers.get(name) is None:
            containers[name] = _attempt(lambda: _cgroup_of(find_container[name]()))
        if containers[name] is not None:
            sample[name] = _attempt(lambda: _read_cgroup(containers[name]))
            if sample[name] is None:
                containers[name] = None
    return {key: value for key, value in sample.items() if value is not None}


def sample_until_stopped(path, base_url, app_pid, shell_pid, k6_label):
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    find_container = {
        service: (lambda service=service: _command(["docker", "compose", "ps", "-q", service]))
        for service in COMPOSE_SERVICES
    }
    find_container["k6"] = lambda: _command(["docker", "ps", "-q", "--filter", "label=" + k6_label])
    containers = {}
    disk = _attempt(lambda: _disk_of(_command(["docker", "info", "-f", "{{.DockerRootDir}}"])))
    sessions = _attempt(_Sessions)
    actuator = _Actuator(base_url)
    next_tick = time.monotonic()
    try:
        with open(path, "a") as out:
            while os.path.exists("/proc/%s" % shell_pid):
                sample = _sample(app_pid, actuator, sessions, containers, find_container, disk)
                out.write(json.dumps(sample) + "\n")
                out.flush()
                next_tick += 1
                delay = next_tick - time.monotonic()
                if delay > 0:
                    time.sleep(delay)
                else:
                    next_tick = time.monotonic()
    finally:
        if sessions:
            sessions.close()


def load(path):
    samples = []
    try:
        with open(path) as f:
            for line in f:
                try:
                    samples.append(json.loads(line))
                except ValueError:
                    continue
    except FileNotFoundError:
        pass
    return samples


def _between(samples, start_millis, end_millis):
    return [s for s in samples if start_millis <= s["at"] < end_millis]


def _value(sample, path):
    for key in path:
        if not isinstance(sample, dict) or key not in sample:
            return None
        sample = sample[key]
    return sample


def _rate(samples, path, scale=1.0):
    """Mean and highest one-tick rate of a cumulative counter, per second, times scale."""
    points = [(s["at"], v) for s in samples if (v := _value(s, path)) is not None]
    steps = [
        (v2 - v1) * scale * 1000 / (t2 - t1)
        for (t1, v1), (t2, v2) in zip(points, points[1:])
        if t2 > t1 and v2 >= v1
    ]
    if not steps:
        return None
    (t1, v1), (t2, v2) = points[0], points[-1]
    mean = (v2 - v1) * scale * 1000 / (t2 - t1) if v2 >= v1 else None
    return {"mean": mean, "max": max(steps)}


def _host_cores(samples):
    points = [(s["host_ticks"], s["cpus"]) for s in samples if "host_ticks" in s]
    steps = [
        (b2 - b1) / (t2 - t1) * cpus
        for ((b1, t1), _), ((b2, t2), cpus) in zip(points, points[1:])
        if t2 > t1
    ]
    if not steps:
        return None
    ((b1, t1), _), ((b2, t2), cpus) = points[0], points[-1]
    return {"mean": (b2 - b1) / (t2 - t1) * cpus, "max": max(steps)}


def _gauge(samples, path):
    values = [v for s in samples if (v := _value(s, path)) is not None]
    return {"mean": sum(values) / len(values), "max": max(values)} if values else None


def _consumer_busy(samples):
    """Each consumer's share of the time spent processing, across the first and last
    samples that report it. A stopped consumer's timer disappears and restarts from zero,
    so a consumer whose total went down is left out rather than read as negative."""
    points = [(s["at"], s["listener_seconds"]) for s in samples if "listener_seconds" in s]
    if len(points) < 2 or points[-1][0] <= points[0][0]:
        return None
    (t1, first), (t2, last) = points[0], points[-1]
    shares = [
        (last[name] - first[name]) * 1000 / (t2 - t1)
        for name in last
        if name in first and last[name] >= first[name]
    ]
    return {"busiest": max(shares), "mean": sum(shares) / len(shares)} if shares else None


def _acquire_wait_ms(samples):
    pools = [s["pool"] for s in samples if "pool" in s]
    if len(pools) < 2:
        return None
    acquired = pools[-1]["acquire_count"] - pools[0]["acquire_count"]
    waited = pools[-1]["acquire_seconds"] - pools[0]["acquire_seconds"]
    return waited * 1000 / acquired if acquired > 0 else None


def _sessions(samples):
    answers = [s["sessions"] for s in samples if "sessions" in s]
    if not answers:
        return None
    states = sorted({state for answer in answers for state in answer})
    return {state: sum(a.get(state, 0) for a in answers) / len(answers) for state in states}


def _rounded(value, digits):
    if isinstance(value, dict):
        return {key: _rounded(v, digits) for key, v in value.items()}
    return round(value, digits) if isinstance(value, float) else value


def summarize(samples, start_millis, end_millis):
    """What each part did over a window: CPU in cores, so 1.0 is one whole core and the
    host's ceiling is its core count; disk_busy and consumer_busy as shares of the time,
    1.0 at the ceiling; pool and session figures as counts."""
    window = _between(samples, start_millis, end_millis)
    if len(window) < 2:
        return None
    megabytes = 1 / 1e6
    return {
        "samples": len(window),
        "host_cores": _rounded(_host_cores(window), 2),
        "app_cores": _rounded(_rate(window, ("app_cpu_seconds",)), 2),
        "postgres_cores": _rounded(_rate(window, ("postgres", "cpu_seconds")), 2),
        "kafka_cores": _rounded(_rate(window, ("kafka", "cpu_seconds")), 2),
        "k6_cores": _rounded(_rate(window, ("k6", "cpu_seconds")), 2),
        "sampler_cores": _rounded(_rate(window, ("sampler_cpu_seconds",)), 3),
        "consumer_busy": _rounded(_consumer_busy(window), 3),
        "disk_busy": _rounded(_rate(window, ("disk_busy_ms",), 1 / 1000), 3),
        "postgres_written_mb_per_sec": _rounded(
            _rate(window, ("postgres", "written_bytes"), megabytes), 1
        ),
        "kafka_written_mb_per_sec": _rounded(
            _rate(window, ("kafka", "written_bytes"), megabytes), 1
        ),
        "pool_active": _rounded(_gauge(window, ("pool", "active")), 2),
        "pool_pending": _rounded(_gauge(window, ("pool", "pending")), 2),
        "pool_acquire_wait_ms": _rounded(_acquire_wait_ms(window), 3),
        "postgres_sessions": _rounded(_sessions(window), 2),
    }


CURVE_COLUMNS = [
    "seconds", "host_cores", "app_cores", "postgres_cores", "kafka_cores", "k6_cores",
    "consumer_busy", "pool_pending", "postgres_sessions", "disk_busy",
]


def curve(samples, origin_millis):
    """One row of CURVE_COLUMNS per tick, for the cell's saturation.jsonl. consumer_busy
    adds up every consumer and postgres_sessions every non-idle session."""
    rows = []
    for previous, sample in zip(samples, samples[1:]):
        pair = [previous, sample]

        def rate(path, scale=1.0):
            step = _rate(pair, path, scale)
            return None if step is None else round(step["mean"], 3)

        host = _host_cores(pair)
        busy = _consumer_busy(pair)
        rows.append([
            round((sample["at"] - origin_millis) / 1000, 1),
            None if host is None else round(host["mean"], 2),
            rate(("app_cpu_seconds",)),
            rate(("postgres", "cpu_seconds")),
            rate(("kafka", "cpu_seconds")),
            rate(("k6", "cpu_seconds")),
            None if busy is None else round(busy["mean"] * len(sample["listener_seconds"]), 3),
            _value(sample, ("pool", "pending")),
            None if "sessions" not in sample else sum(sample["sessions"].values()),
            rate(("disk_busy_ms",), 1 / 1000),
        ])
    return {"columns": CURVE_COLUMNS, "samples": rows}


if __name__ == "__main__":
    sample_until_stopped(*sys.argv[1:6])
