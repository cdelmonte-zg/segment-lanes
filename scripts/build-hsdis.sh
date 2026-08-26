#! /bin/bash
#
# Build an hsdis that JDK 25 can load and that decodes AVX-512 completely.
#
# Two things go wrong with the obvious routes. Distribution packages such as
# libhsdis0-fcml export the old ABI symbol `decode_instructions`, while JDK 25
# requires `decode_instructions_virtual`, so PrintAssembly silently degrades to
# a byte dump. And the capstone backend, with the 4.0.2 that Ubuntu 24.04
# ships, fails to decode some EVEX encodings: they appear in the listing as
# `.byte 0x62` followed by mnemonics that belong to no real instruction. The
# LLVM backend has neither problem.
#
# No sudo needed. Requires llvm-<n>-dev (for llvm-config and the disassembler)
# and a JDK 25 for jni.h.
#
#   scripts/build-hsdis.sh
#   LD_LIBRARY_PATH=$PWD/hsdis/lib java -jar target/benchmarks.jar ... -prof perfasm
set -euo pipefail
cd "$(dirname "$0")/.."

JDK="${JDK:-/usr/lib/jvm/java-25-openjdk-amd64}"
OUT=hsdis
SRC="$OUT/jdk/src/utils/hsdis"

command -v llvm-config >/dev/null || { echo "llvm-config not found: install llvm-<n>-dev" >&2; exit 1; }

# Fetch only src/utils/hsdis from the JDK 25 GA tag.
if [ ! -d "$SRC" ]; then
	rm -rf "$OUT/jdk"
	git clone --depth 1 --filter=blob:none --sparse \
		https://github.com/openjdk/jdk.git -b jdk-25-ga "$OUT/jdk"
	( cd "$OUT/jdk" && git sparse-checkout set src/utils/hsdis )
fi

mkdir -p "$OUT/lib"

# LLVM_DEFAULT_TRIPLET is normally injected by the JDK build system; llvm-config
# knows the same value. The cxxflags filtering drops flags that clash with the
# way this single translation unit is compiled.
triple=$(llvm-config --host-target)
g++ -O2 -fPIC -shared \
	-I"$SRC" -I"$JDK/include" -I"$JDK/include/linux" \
	-DLLVM_DEFAULT_TRIPLET="\"${triple}\"" \
	$(llvm-config --cxxflags | sed 's/-fno-exceptions//;s/-std=c++[0-9]*//') -std=c++17 \
	"$SRC/llvm/hsdis-llvm.cpp" \
	$(llvm-config --ldflags) \
	$(llvm-config --libs x86disassembler x86desc x86info mc support) \
	$(llvm-config --system-libs) \
	-o "$OUT/lib/hsdis-amd64.so"

nm -D "$OUT/lib/hsdis-amd64.so" | grep -q decode_instructions_virtual || {
	echo "ERROR: decode_instructions_virtual not exported" >&2; exit 1; }

echo "OK: $OUT/lib/hsdis-amd64.so  (LLVM $(llvm-config --version) backend, triple ${triple})"
echo "Use it with:  LD_LIBRARY_PATH=\$PWD/$OUT/lib java -jar target/benchmarks.jar ... -prof perfasm"
