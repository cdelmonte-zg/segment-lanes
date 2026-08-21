#! /bin/bash
set -euo pipefail


if [ -n "$(git status --porcelain -- ':!results')" ]; then
	echo "working tree dirty: commit before measuring" >&2
	exit 1
fi


commit=$(git rev-parse --short HEAD)
run_date=$(date +%F)

mvn -q clean package

filename_txt="results/${run_date}-${1}-avgt.txt"
filename_json="results/${run_date}-${1}-avgt.json"
jdk_version=$(java --version 2>&1 | head -1)
mkdir -p results

cmd="java -jar target/benchmarks.jar -f 3 -wi 5 -i 5 -rf json -rff ${filename_json} ${@:2}"

echo "# commit: ${commit} JDK: ${jdk_version}  CPU: Ryzen 9 7950X3D 
# ${cmd}" > "${filename_txt}"

${cmd} | tee -a "${filename_txt}"