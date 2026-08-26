#! /bin/bash
set -euo pipefail


if [ -n "$(git status --porcelain -- ':!results')" ]; then
	echo "working tree dirty: commit before measuring" >&2
	exit 1
fi


commit=$(git rev-parse --short HEAD)
run_date=$(date +%F)

# session.sh builds once for the whole session and sets this to skip the rebuild
[ -n "${BENCH_SKIP_PACKAGE:-}" ] || mvn -q clean package

filename_txt="results/${run_date}-${1}-avgt.txt"
filename_json="results/${run_date}-${1}-avgt.json"
jdk_version=$(java --version 2>&1 | head -1)
mkdir -p results

# BENCH_PIN pins the run to a set of cores, e.g. BENCH_PIN=8-15,24-31 for one CCD
pin=""
if [ -n "${BENCH_PIN:-}" ]; then
	pin="taskset -c ${BENCH_PIN} "
fi

cmd="${pin}java -jar target/benchmarks.jar -f 3 -wi 5 -i 5 -rf json -rff ${filename_json} ${@:2}"

echo "# commit: ${commit} JDK: ${jdk_version}  CPU: Ryzen 9 7950X3D 
# ${cmd}" > "${filename_txt}"

${cmd} | tee -a "${filename_txt}"