#! /bin/bash
#
# One full measurement session: one machine state, one manifest, every file
# the article can quote. Run it from the repo root:
#
#   scripts/session.sh final                         # any core, scheduler decides
#   BENCH_PIN=8-15,24-31 scripts/session.sh final    # pinned to one CCD
#
# This machine (7950X3D) has two CCDs with different L3: 0-7,16-23 carry the
# 96 MB 3D V-Cache and clock lower; 8-15,24-31 have 32 MB and clock higher.
# None of the measured sizes fits in either L3 differently (16 KB and 1 MB sit
# in L1/L2, 256 MB overflows both), so the CCD is chosen for a steady clock,
# not for cache. Whichever is used, the manifest records it.
#
# Everything here runs under bench-system setup (governor=performance, perf
# counters unlocked), so these numbers are NOT comparable line by line with
# the M1-M4 reports, which were taken on a stock machine. This session is a
# new self-consistent baseline; the manifest records the state it ran in.
#
# The machine is restored on EXIT, including on failure or Ctrl-C.
set -euo pipefail

TAG="${1:?usage: scripts/session.sh <tag>   (e.g. final)}"
PIN="${BENCH_PIN:-}"
HSDIS_LIB="${HSDIS_LIB:-$PWD/hsdis/lib}"

# Sizes and variants. PERFASM_AT is a single size on purpose: at 16 M the
# listing is too large to read. list is left out of the profiled runs (its
# profile is dominated by pointer chasing and says nothing new).
PERFASM_AT=1024
PERFNORM_AT=16777216
PROFILED="array segmentScalar arrayVector segmentVector arrayLoadControl"

# The ladder plus its control. Experimental kernels (SegmentVectorUnalignedDot
# and anything else added for a one-off question) are deliberately excluded:
# they have their own scripts and must not drift into the session numbers.
CANONICAL="DotProductBench.(array|list|segmentScalar|arrayVector|segmentVector|arrayLoadControl)\$"

if [ -n "$(git status --porcelain -- ':!results')" ]; then
	echo "working tree dirty: commit before measuring" >&2
	exit 1
fi

run_date=$(date +%F)
commit=$(git rev-parse --short HEAD)
jdk_version=$(java --version 2>&1 | head -1)
cpu_model=$(lscpu | sed -n 's/^Model name: *//p' | head -1)

java_spec=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')
if [ "${java_spec}" != "25" ]; then
	echo "expected JDK 25 on PATH, found ${java_spec:-unknown}" >&2
	exit 1
fi
OUT_DIR="results/session"
export BENCH_OUT="${OUT_DIR}"
manifest="${OUT_DIR}/${run_date}-${TAG}-manifest.txt"
mkdir -p "${OUT_DIR}"

pin=""
if [ -n "${PIN}" ]; then
	pin="taskset -c ${PIN} "
fi

echo "==> building once for the whole session"
mvn -q clean package
export BENCH_SKIP_PACKAGE=1
export BENCH_PIN="${PIN}"

echo "==> machine setup (restored automatically on exit)"
scripts/bench-system.sh setup
trap 'echo "==> restoring machine state"; scripts/bench-system.sh restore' EXIT

{
	echo "# session: ${TAG}   date: ${run_date}"
	echo "# commit: ${commit}"
	echo "# JDK: ${jdk_version}"
	echo "# CPU: ${cpu_model}"
	echo "# pinning: ${PIN:-none (all cores)}"
	echo "# hsdis: ${HSDIS_LIB}"
	if [ -f "${HSDIS_LIB}/hsdis-amd64.so" ]; then
		echo "# hsdis backend: $(nm -D "${HSDIS_LIB}/hsdis-amd64.so" 2>/dev/null | grep -qi llvm && echo "LLVM (decodes AVX-512 fully)" || echo "unknown, check that AVX-512 decodes: capstone 4.x does not")"
	fi
	echo "# perfasm at size ${PERFASM_AT}, perfnorm at size ${PERFNORM_AT}"
	echo "# JVM layout flags (a lab about layout should record these):"
	java -XX:+PrintFlagsFinal -version 2>/dev/null \
		| grep -E '^ *(int|bool|intx) +(ObjectAlignmentInBytes|UseCompactObjectHeaders|UseCompressedOops|UseCompressedClassPointers|UseAVX) ' \
		| awk '{print "#   "$2" = "$4}'
	echo "# machine state as measured:"
	scripts/bench-system.sh status | sed 's/^/#   /'
	echo "# files:"
} > "${manifest}"

note() { echo "#   $1" >> "${manifest}"; }

# --- timings and allocation, all variants, all sizes -----------------------

echo "==> avgt (all variants, all sizes)"
scripts/bench.sh "${TAG}" "${CANONICAL}"
note "${run_date}-${TAG}-avgt.{txt,json}"

echo "==> gc profile"
scripts/bench.sh "${TAG}-gc" "${CANONICAL}" -prof gc
note "${run_date}-${TAG}-gc-avgt.{txt,json}"

# --- disassembly, one variant per file, in-cache size ----------------------

if [ ! -f "${HSDIS_LIB}/hsdis-amd64.so" ] && [ -x scripts/build-hsdis.sh ]; then
	echo "==> hsdis not found, building it (LLVM backend)"
	scripts/build-hsdis.sh || echo "!!! hsdis build failed, perfasm will be skipped" >&2
fi

if [ -f "${HSDIS_LIB}/hsdis-amd64.so" ]; then
	for v in ${PROFILED}; do
		out="${OUT_DIR}/${run_date}-${TAG}-perfasm-$(echo "$v" | tr '[:upper:]' '[:lower:]').txt"
		cmd="${pin}java -jar target/benchmarks.jar 'DotProductBench.${v}$' -p size=${PERFASM_AT} -f 1 -wi 5 -i 5 -prof perfasm"
		echo "==> perfasm ${v} @${PERFASM_AT}"
		echo "# commit: ${commit} JDK: ${jdk_version}  CPU: ${cpu_model}
# ${cmd}
# ns/op in this file are NOT citable: the profiler perturbs the run." > "${out}"
		LD_LIBRARY_PATH="${HSDIS_LIB}" ${pin} java -jar target/benchmarks.jar \
			"DotProductBench.${v}\$" -p size=${PERFASM_AT} \
			-f 1 -wi 5 -i 5 -prof perfasm 2>&1 | tee -a "${out}"
		note "$(basename "${out}")"
	done
else
	echo "!!! hsdis not found at ${HSDIS_LIB}, skipping perfasm" >&2
	note "perfasm SKIPPED: no hsdis at ${HSDIS_LIB}"
fi

# --- hardware counters at the size where memory dominates -----------------

out="${OUT_DIR}/${run_date}-${TAG}-perfnorm-${PERFNORM_AT}.txt"
regex="DotProductBench.($(echo ${PROFILED} | tr ' ' '|'))\$"   # profiled subset, list excluded
cmd="${pin}java -jar target/benchmarks.jar '${regex}' -p size=${PERFNORM_AT} -f 3 -wi 5 -i 5 -prof perfnorm"
echo "==> perfnorm @${PERFNORM_AT}"
echo "# commit: ${commit} JDK: ${jdk_version}  CPU: ${cpu_model}
# ${cmd}" > "${out}"
${pin} java -jar target/benchmarks.jar "${regex}" -p size=${PERFNORM_AT} \
	-f 3 -wi 5 -i 5 -prof perfnorm 2>&1 | tee -a "${out}"
note "$(basename "${out}")"

echo
echo "==> session ${TAG} complete. Manifest: ${manifest}"
cat "${manifest}"
