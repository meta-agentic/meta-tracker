#!/usr/bin/env bash
# THROWAWAY PROTOTYPE (VEC-67 spike).
#   ./run.sh test   the Vitest cases (forms, transitions, violations, conformance)
#   ./run.sh java   the same conformance cases through the Java evaluator
#   ./run.sh demo   the forms in a browser on http://localhost:5299
# Needs `npm ci` in web/ first; borrows its node_modules through a symlink.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
web="$here/../../../web"
[ -d "$web/node_modules" ] || { echo "run npm ci in web/ first" >&2; exit 1; }
ln -sfn ../../../web/node_modules "$here/node_modules"
cd "$here"
case "${1:-test}" in
  test) exec ./node_modules/.bin/vitest run ;;
  java) exec java Conformance.java ;;
  demo) exec ./node_modules/.bin/vite --strictPort ;;
  *) echo "usage: $0 test|java|demo" >&2; exit 2 ;;
esac
