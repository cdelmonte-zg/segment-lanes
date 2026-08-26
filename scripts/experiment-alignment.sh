#! /bin/bash
#
# Controlled experiment: does segment alignment change the running time, and
# does the answer depend on the working-set size?
#
#   scripts/experiment-alignment.sh                       # any core
#   BENCH_PIN=8-15,24-31 scripts/experiment-alignment.sh  # pinned to one CCD
#
# SegmentVectorUnalignedDot is SegmentVectorDot with one line changed: the
# arena is asked for the natural 8-byte alignment of the element instead of 64
# bytes. Both run in the SAME JMH session, so alignment is the single variable
# rather than a comparison across two runs with two machine states.
#
# arrayVector is measured alongside as a reference point, not as a control.
#
# Ten forks, not three: the quantity of interest includes the fork-to-fork
# distribution, which turned out to be bimodal at 1024. Read the per-fork
# numbers in the .json, not only the mean; see the report.
#
# Everything runs under bench-system setup, restored on exit.
set -euo pipefail
cd "$(dirname "$0")/.."

TAG="${1:-alignment-by-size}"
PIN="${BENCH_PIN:-}"
HSDIS_LIB="${HSDIS_LIB:-$PWD/hsdis/lib}"
VARIANTS="arrayVector segmentVector segmentVectorUnaligned"
REGEX="DotProductBench.(arrayVector|segmentVector|segmentVectorUnaligned)\$"
PERFASM_AT=1024
PERFNORM_AT=1024

if ! [ -f src/main/java/dev/cdelmonte/segmentlanes/compute/SegmentVectorUnalignedDot.java ]; then
	echo "SegmentVectorUnalignedDot is missing: this experiment needs both variants" >&2
	exit 1
fi

if [ -n "$(git status --porcelain -- ':!results')" ]; then
	echo "working tree dirty: commit before measuring" >&2
	exit 1
fi

run_date=$(date +%F)
commit=$(git rev-parse --short HEAD)
jdk_version=$(java --version 2>&1 | head -1)
cpu_model=$(lscpu | sed -n 's/^Model name: *//p' | head -1)

pin=""
if [ -n "${PIN}" ]; then
	pin="taskset -c ${PIN} "
fi

echo "==> building"
mvn -q clean package
export BENCH_SKIP_PACKAGE=1
OUT_DIR="results/experiments"
export BENCH_OUT="${OUT_DIR}"
mkdir -p "${OUT_DIR}"
export BENCH_PIN="${PIN}"

if [ ! -f "${HSDIS_LIB}/hsdis-amd64.so" ] && [ -x scripts/build-hsdis.sh ]; then
	echo "==> hsdis not found, building it (LLVM backend)"
	scripts/build-hsdis.sh || echo "!!! hsdis build failed, perfasm will be skipped" >&2
fi

echo "==> machine setup (restored automatically on exit)"
scripts/bench-system.sh setup
trap 'echo "==> restoring machine state"; scripts/bench-system.sh restore' EXIT

echo "==> timings, all sizes, 10 forks"
BENCH_FORKS=10 scripts/bench.sh "${TAG}" "${REGEX}"

echo "==> counters at ${PERFNORM_AT}"
scripts/bench.sh "${TAG}-perfnorm-${PERFNORM_AT}" "${REGEX}" -p size=${PERFNORM_AT} -prof perfnorm

if [ -f "${HSDIS_LIB}/hsdis-amd64.so" ]; then
	for v in ${VARIANTS}; do
		out="${OUT_DIR}/${run_date}-${TAG}-perfasm-$(echo "$v" | tr '[:upper:]' '[:lower:]').txt"
		cmd="${pin}java -jar target/benchmarks.jar 'DotProductBench.${v}\$' -p size=${PERFASM_AT} -f 1 -wi 5 -i 5 -prof perfasm"
		echo "==> perfasm ${v} @${PERFASM_AT}"
		echo "# commit: ${commit} JDK: ${jdk_version}  CPU: ${cpu_model}
# ${cmd}
# ns/op in this file are NOT citable: the profiler perturbs the run, and here it
# also shifts which fork mode the unaligned variant lands in." > "${out}"
		LD_LIBRARY_PATH="${HSDIS_LIB}" ${pin} java -jar target/benchmarks.jar \
			"DotProductBench.${v}\$" -p size=${PERFASM_AT} \
			-f 1 -wi 5 -i 5 -prof perfasm 2>&1 | tee -a "${out}" > /dev/null
		printf "    %-24s undecoded EVEX: %s\n" "$v" "$(grep -c '\.byte.*0x62' "${out}")"
	done
else
	echo "!!! no hsdis at ${HSDIS_LIB}, skipping perfasm" >&2
fi

echo
echo "==> done. Per-fork values matter here:"
echo "    jq '.[] | select(.params.size==\"1024\") | [.benchmark, (.primaryMetric.rawData[] | add/length)]' ${OUT_DIR}/${run_date}-${TAG}-avgt.json"
