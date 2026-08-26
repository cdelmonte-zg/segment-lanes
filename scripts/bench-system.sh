#!/usr/bin/env bash
#
# Put the machine into / out of a state where benchmark numbers are trustworthy.
# Touches machine state (needs sudo): CPU governor + perf counter access.
#
#   ./bench-system.sh setup     # before benchmarking: pin governor, unlock perf
#   ./bench-system.sh restore   # after: revert everything to the prior state
#   ./bench-system.sh status    # show current governor / perf settings
#
# NONE of this is needed for `mvn test` — tests run on a stock machine. This is
# only for `target/benchmarks.jar` throughput / -prof perfnorm runs.
#
# `setup` records the prior governor / perf_event_paranoid / kptr_restrict so
# `restore` puts back exactly what was there. If no record exists (e.g. you set
# things up by hand before using this script), restore falls back to the common
# distro defaults: governor=powersave, perf_event_paranoid=4, kptr_restrict=1.
set -euo pipefail

STATE="${XDG_CACHE_HOME:-$HOME/.cache}/segment-lanes-bench-state"

gov()    { cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo "?"; }
parano() { cat /proc/sys/kernel/perf_event_paranoid 2>/dev/null || echo "?"; }
kptr()   { cat /proc/sys/kernel/kptr_restrict 2>/dev/null || echo "?"; }

status() {
  echo "governor            = $(gov)"
  echo "perf_event_paranoid = $(parano)"
  echo "kptr_restrict       = $(kptr)"
  [ -f "$STATE" ] && { echo "saved prior state:"; sed 's/^/  /' "$STATE"; } || echo "(no saved prior state)"
}

case "${1:-}" in
  setup)
    if ! command -v cpupower >/dev/null 2>&1; then
      echo "cpupower not found — install it first:" >&2
      echo "  sudo apt install -y linux-tools-common linux-tools-generic" >&2
      exit 1
    fi
    mkdir -p "$(dirname "$STATE")"
    # Do not overwrite a saved state: setting up twice would record the
    # already-modified settings as the prior ones, and restore would then put
    # the machine back into benchmark mode instead of out of it.
    if [ -f "$STATE" ]; then
      echo "prior state already recorded (setup ran before), keeping it:"
      sed 's/^/  /' "$STATE"
    else
      {
        echo "GOV=$(gov)"
        echo "PARANOID=$(parano)"
        echo "KPTR=$(kptr)"
      } > "$STATE"
      echo "saved prior state to $STATE:"; sed 's/^/  /' "$STATE"
    fi
    sudo cpupower frequency-set -g performance
    sudo sysctl kernel.perf_event_paranoid=0 kernel.kptr_restrict=0
    echo "benchmark mode ON  (governor=performance, perf counters unlocked)"
    ;;

  restore)
    GOV=powersave; PARANOID=4; KPTR=1          # fallback = common distro defaults
    # shellcheck disable=SC1090
    [ -f "$STATE" ] && . "$STATE"
    sudo cpupower frequency-set -g "${GOV:-powersave}"
    sudo sysctl kernel.perf_event_paranoid="${PARANOID:-4}" kernel.kptr_restrict="${KPTR:-1}"
    
    rm -f "$STATE"
    echo "restored  (governor=${GOV:-powersave}, perf_event_paranoid=${PARANOID:-4}, kptr_restrict=${KPTR:-1})"
    echo "hsdis: use LD_LIBRARY_PATH pointing at a hsdis build; see README"
    ;;

  status) status ;;

  *) echo "usage: $0 {setup|restore|status}" >&2; exit 2 ;;
esac
