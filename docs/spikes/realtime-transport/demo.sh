#!/usr/bin/env bash
# Two-instance demonstration for the real-time transport spike.
#
# Everything runs in containers on one Docker network, as pods share a cluster network:
# PostgreSQL (the staging image), instance A, instance B, and the probe client. Ports
# 5740-5742 are also published on 127.0.0.1 so the streams can be watched with curl.
#
#   ./demo.sh build                 package the prototype (needs the root Quarkus BOM in ~/.m2)
#   ./demo.sh up <carrierA> <carrierB> [publishDelayMsOnA]
#   ./demo.sh probe <mode> [args]   run probe/Probe.java on the network (A = http://vec46-a:8080)
#   ./demo.sh all                   sections 1-9, as recorded in ../realtime-transport.md
#   ./demo.sh down                  remove the containers and the network
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
NET=vec46-net
PG_IMAGE=postgres:16.15-alpine3.24
JDK_IMAGE=eclipse-temurin:25-jdk
A=http://vec46-a:8080
B=http://vec46-b:8080

build() {
  (cd "$DIR" && mvn -B -ntp -q package -DskipTests)
}

down() {
  docker rm -f vec46-a vec46-b vec46-pg >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
}

instance() { # name port carrier publishDelayMs [feedDelayMs]
  docker rm -f "vec46-$1" >/dev/null 2>&1 || true
  docker run -d --name "vec46-$1" --network "$NET" -p "127.0.0.1:$2:8080" \
    -v "$DIR/target/quarkus-app:/app:ro" "$JDK_IMAGE" \
    java -Dquarkus.http.port=8080 -Dspike.instance="$(echo "$1" | tr a-z A-Z)" -Dspike.carrier="$3" \
         -Dspike.publish-delay-ms="$4" -Dspike.feed-delay-ms="${5:-0}" \
         -Dquarkus.datasource.reactive.url=postgresql://vec46-pg:5432/vec46 \
         -jar /app/quarkus-run.jar >/dev/null
}

stop() { # pids: end background curls without job-control noise
  kill "$@" 2>/dev/null || true
  wait "$@" 2>/dev/null || true
}

wait_http() {
  for _ in $(seq 1 60); do
    curl -sf "http://127.0.0.1:$1/spike/whoami" >/dev/null 2>&1 && return 0
    sleep 0.5
  done
  echo "instance on $1 did not start" >&2
  exit 1
}

up() { # carrierA carrierB [publishDelayMsOnA] [feedDelayMsOnB]
  docker network inspect "$NET" >/dev/null 2>&1 || docker network create "$NET" >/dev/null
  if ! docker inspect vec46-pg >/dev/null 2>&1; then
    docker run -d --name vec46-pg --network "$NET" -p 127.0.0.1:5740:5432 \
      -e POSTGRES_USER=vec46 -e POSTGRES_PASSWORD=vec46 -e POSTGRES_DB=vec46 "$PG_IMAGE" >/dev/null
    until docker exec vec46-pg pg_isready -U vec46 -d vec46 >/dev/null 2>&1; do sleep 0.5; done
    sleep 1
  fi
  instance a 5741 "$1" "${3:-0}"
  instance b 5742 "$2" 0 "${4:-0}"
  wait_http 5741
  wait_http 5742
  echo "A: $(curl -s 127.0.0.1:5741/spike/whoami)  B: $(curl -s 127.0.0.1:5742/spike/whoami)"
}

reset() {
  curl -s -X POST 127.0.0.1:5741/spike/reset
}

probe() {
  docker run --rm --network "$NET" -v "$DIR/probe:/probe:ro" "$JDK_IMAGE" java /probe/Probe.java "$@"
}

# Subscribe curl to VEC on both instances and to OPS and WEB on B, perform $1 (a function)
# against A, print what each client received.
watch_both() {
  local out; out="$(mktemp -d)"
  curl -sN "127.0.0.1:5741/api/v1/workspaces/VEC/events?after=0" >"$out/a" & local pa=$!
  curl -sN "127.0.0.1:5742/api/v1/workspaces/VEC/events?after=0" >"$out/b" & local pb=$!
  curl -sN "127.0.0.1:5742/api/v1/workspaces/OPS/events?after=0" >"$out/ops" & local po=$!
  curl -sN "127.0.0.1:5742/api/v1/workspaces/WEB/events?after=0" >"$out/web" & local pw=$!
  sleep 1
  "$1"
  sleep 1
  stop "$pa" "$pb" "$po" "$pw"
  echo "--- client of A, workspace VEC, received:"; cat "$out/a"
  echo "--- client of B, workspace VEC, received:"; cat "$out/b"
  echo "--- client of B, workspace OPS (also on the scrum template), received:"; cat "$out/ops"
  echo "--- client of B, workspace WEB (kanban template), received:"; cat "$out/web"
  rm -rf "$out"
}

col() { echo "$SEED" | sed -E "s/.*\"$1\":\"([0-9a-f-]+)\".*/\1/"; }

writes() {
  curl -s -o /dev/null -X POST 127.0.0.1:5741/api/v1/workspaces/VEC/items/VEC-1/move \
    -H 'Content-Type: application/json' -H 'Vectis-Origin: tab-7f3a' \
    -d "{\"columnId\":\"$(col in-progress)\",\"rank\":\"k\"}"
  curl -s -o /dev/null -X PATCH 127.0.0.1:5741/api/v1/workspaces/VEC/items/VEC-2 \
    -H 'Content-Type: application/json' -d '{"title":"Highlight remote updates on every board","fields":{"storyPoints":5}}'
  curl -s -o /dev/null -X POST 127.0.0.1:5741/api/v1/workspaces/VEC/items \
    -H 'Content-Type: application/json' \
    -d "{\"title\":\"Resume a stream from Last-Event-ID\",\"boardId\":\"$(col boardId)\",\"columnId\":\"$(col todo)\",\"rank\":\"t\",\"fields\":{\"type\":\"task\"}}"
  curl -s -o /dev/null -X POST 127.0.0.1:5741/spike/workspaces/VEC/configuration \
    -H 'Content-Type: application/json' -d '{"cause":{"kind":"delta"}}'
  curl -s -o /dev/null -X POST 127.0.0.1:5741/spike/templates/scrum/publish \
    -H 'Content-Type: application/json' -d '{"version":2}'
}

all() {
  build
  down

  echo "== 1. no carrier: A and B each broadcast in their own JVM"
  up none none; SEED="$(reset)"
  watch_both writes
  probe latency --write "$A" --watch "$B" --subscribers 1 --count 50 --rate 20

  echo; echo "== 2. carrier pg: NOTIFY doorbell in the write transaction, event rows from workspace_event"
  up pg pg; SEED="$(reset)"
  watch_both writes
  echo "-- a client of B whose cursor is ahead of the stream (Last-Event-ID: 999) is told to resync:"
  resync_cursor

  echo; echo "== 3. latency, carrier pg"
  reset >/dev/null
  probe latency --write "$A" --watch "$B" --subscribers 1 --count 1000 --rate 20
  probe latency --write "$A" --watch "$A,$B" --subscribers 200 --count 1000 --rate 20
  probe latency --write "$A,$B" --watch "$A,$B" --subscribers 200 --count 2000 --rate 100 --writers 4

  echo; echo "== 4. payload larger than NOTIFY's 8000 bytes, carrier pg"
  probe payload --write "$A" --watch "$B" --size 9000

  echo; echo "== 5. own write against a concurrent remote write, carrier pg"
  reset >/dev/null
  probe race --remote "$A" --own "$B" --trials 300
  echo "-- again, with B's feed stalled 20 ms (a GC pause or a slow read)"
  up pg pg 0 20; reset >/dev/null
  probe race --remote "$A" --own "$B" --trials 300
  up pg pg

  echo; echo "== 6. resume: the client's instance dies, it reconnects to the other with Last-Event-ID"
  resume

  echo; echo "== 7. the LISTEN connection drops: doorbells are lost, events are not"
  listen_drop

  echo; echo "== 8. a deaf LISTEN connection (doorbells ignored on B): the catch-up poll bounds the latency"
  reset >/dev/null
  curl -s -X POST "127.0.0.1:5742/spike/deaf?on=true"; echo
  probe latency --write "$A" --watch "$B" --subscribers 1 --count 100 --rate 10
  curl -s -X POST "127.0.0.1:5742/spike/deaf?on=false"; echo

  echo; echo "== 9. publish after commit (broker-shaped dual write), A's publish stalled 300 ms"
  up pg-postcommit pg-postcommit 300; SEED="$(reset)"
  probe dualwrite --slow "$A" --fast "$B"
  probe payload --write "$B" --watch "$B" --size 9000
  docker logs vec46-b 2>&1 | grep -m1 "post-commit publish" || true

  down
}

resync_cursor() {
  local out; out="$(mktemp)"
  curl -sN -H 'Last-Event-ID: 999' "127.0.0.1:5742/api/v1/workspaces/VEC/events" >"$out" & local p=$!
  sleep 1
  stop "$p"
  cat "$out"
  rm -f "$out"
}

resume() {
  SEED="$(reset)"
  local out; out="$(mktemp -d)"
  curl -sN "127.0.0.1:5742/api/v1/workspaces/VEC/events?after=0" >"$out/b" & local pb=$!
  sleep 1
  for t in one two; do
    curl -s -o /dev/null -X PATCH 127.0.0.1:5741/api/v1/workspaces/VEC/items/VEC-1 -H 'Content-Type: application/json' -d "{\"title\":\"$t\"}"
  done
  sleep 0.5
  docker kill vec46-b >/dev/null
  stop "$pb"
  local last; last="$(grep '^id:' "$out/b" | tail -1 | cut -d: -f2)"
  echo "client of B saw up to id $last, then B was killed"
  for t in three four five; do
    curl -s -o /dev/null -X PATCH 127.0.0.1:5741/api/v1/workspaces/VEC/items/VEC-1 -H 'Content-Type: application/json' -d "{\"title\":\"$t\"}"
  done
  curl -sN -H "Last-Event-ID: $last" "127.0.0.1:5741/api/v1/workspaces/VEC/events" >"$out/a" & local pa=$!
  sleep 1
  stop "$pa"
  echo "--- reconnected to A with Last-Event-ID: $last, received:"
  grep -E '^(id|event):|"title"' "$out/a" | sed -E 's/^data:.*"title":"([^"]*)".*/data: ...title=\1.../'
  rm -rf "$out"
  instance b 5742 pg 0; wait_http 5742
}

listen_drop() {
  SEED="$(reset)"
  local out; out="$(mktemp -d)"
  curl -sN "127.0.0.1:5742/api/v1/workspaces/VEC/events?after=0" >"$out/b" & local pb=$!
  sleep 1
  docker exec vec46-pg psql -U vec46 -d vec46 -qAt -c \
    "select count(pg_terminate_backend(pid)) || ' LISTEN session(s) terminated' from pg_stat_activity where query like 'LISTEN%'"
  for i in 1 2 3 4 5; do
    curl -s -o /dev/null -X PATCH 127.0.0.1:5741/api/v1/workspaces/VEC/items/VEC-1 -H 'Content-Type: application/json' -d "{\"title\":\"during outage $i\"}"
  done
  sleep 1.5
  stop "$pb"
  echo "client of B received (id, committed at):"
  paste -d' ' <(grep '^id:' "$out/b") <(grep '^data:' "$out/b" | sed -E 's/.*"at":"([^"]*)".*/\1/')
  docker logs vec46-b 2>&1 | grep -E "LISTEN .*established" | tail -1
  rm -rf "$out"
}

case "${1:-all}" in
  build) build ;;
  up) shift; up "$@" ;;
  probe) shift; probe "$@" ;;
  down) down ;;
  all) all ;;
  *) echo "usage: $0 build | up <carrierA> <carrierB> [delayA] | probe <mode> ... | all | down" >&2; exit 2 ;;
esac
