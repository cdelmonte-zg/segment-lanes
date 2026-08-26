#! /bin/bash
set -euo pipefail


if [ -n "$(git status --porcelain -- ':!results')" ]; then
	echo "working tree dirty: commit before measuring" >&2
	exit 1
fi


commit=$(git rev-parse --short HEAD)
run_date=$(date +%F)
cpu_model=$(lscpu | sed -n 's/^Model name: *//p' | head -1)

# The benchmark jar runs on whatever `java` is first on PATH, which is not
# necessarily the toolchain Maven compiled with. Fail loudly instead of
# producing a result file that silently mixes the two.
java_spec=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')
if [ "${java_spec}" != "25" ]; then
	echo "expected JDK 25 on PATH, found ${java_spec:-unknown}" >&2
	exit 1
fi

# session.sh builds once for the whole session and sets this to skip the rebuild
[ -n "${BENCH_SKIP_PACKAGE:-}" ] || mvn -q clean package

filename_txt="results/${run_date}-${1}-avgt.txt"
filename_json="results/${run_date}-${1}-avgt.json"
jdk_version=$(java --version 2>&1 | head -1)
mkdir -p results

# BENCH_FORKS raises the fork count when the quantity of interest is the
# fork-to-fork spread itself: 3 forks are 3 samples of it.
forks="${BENCH_FORKS:-3}"

# BENCH_PIN pins the run to a set of cores, e.g. BENCH_PIN=8-15,24-31 for one CCD
pin=""
if [ -n "${BENCH_PIN:-}" ]; then
	pin="taskset -c ${BENCH_PIN} "
fi

cmd="${pin}java -jar target/benchmarks.jar -f ${forks} -wi 5 -i 5 -rf json -rff ${filename_json} ${@:2}"

echo "# commit: ${commit} JDK: ${jdk_version}  CPU: ${cpu_model}
# ${cmd}" > "${filename_txt}"

${cmd} | tee -a "${filename_txt}"