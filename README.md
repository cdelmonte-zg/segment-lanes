# Segment Lanes

Companion lab for the article *"Jenseits des Objektgraphen: MemorySegment, Vector API und das neue Datenmodell der JVM"*.

One numeric kernel (dot product), five data representations of the same hot path:

| Variant | Representation |
|---|---|
| `BoxedListDot` | `List<Double>`: boxing, references, indirection |
| `PrimitiveArrayDot` | `double[]`: dense primitive layout, scalar loop |
| `SegmentScalarDot` | `MemorySegment`: explicit region and layout, scalar loop |
| `ArrayVectorDot` | `double[]` + Vector API: explicit SIMD lanes |
| `SegmentVectorDot` | `MemorySegment` + Vector API: explicit layout and explicit compute |

All variants fill their vectors from the same seed, so every `compute()` runs over the same values in a different physical form.

## Requirements and usage

JDK 25 (Vector API is an incubator module), Maven 3.8+ with a JDK 25 toolchain entry.

```bash
mvn verify
java -jar target/benchmarks.jar
```

## A note on the numbers

Benchmark results in this repo and in the article are valid only for the machine, JDK build, and ISA they were measured on. Vectorization behavior (and thus every ratio between variants) depends on the CPU's SIMD capabilities; do not transfer the ratios elsewhere. Run the benchmarks on your own hardware instead.

## Measuring

Official runs go through the wrapper script:

```bash
scripts/bench.sh m1              # full run: -f 3 -wi 5 -i 5, txt + json in results/
scripts/bench.sh m1-gc -prof gc  # same, with the GC profiler
```

The script refuses to run on a dirty working tree, rebuilds the jar from
scratch, and stamps every result file with the commit hash, JDK build, and
CPU it was measured on. Files in results/ are the raw data the article
quotes; the .json twins feed the plotting scripts.

Correctness is checked separately from measurement: mvn test asserts that
all variants produce bit-identical results for the same size and seed.

### Assembly verification

Hot-loop disassembly runs need perf counters unlocked and an hsdis library
(not shipped with the JDK; any binutils-based build works):

```bash
scripts/bench-system.sh setup    # governor + perf counters; `restore` when done
LD_LIBRARY_PATH=<path-to-hsdis-dir> \
java -jar target/benchmarks.jar 'DotProductBench.array$' -p size=1024 \
-f 1 -wi 5 -i 5 -prof perfasm | tee results/<date>-<tag>-perfasm.txt
```

See docs/reading-perfasm.md for how to read the output.