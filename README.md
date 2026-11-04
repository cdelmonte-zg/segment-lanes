# Segment Lanes

One numeric kernel, the dot product, in five representations of the same
hot path on the JVM. The lab measures what each representation costs and
what the JIT makes of it.

| Variant | Representation |
|---|---|
| `BoxedListDot` | `List<Double>`: boxing, references, indirection |
| `PrimitiveArrayDot` | `double[]`: dense primitive layout, scalar loop |
| `SegmentScalarDot` | `MemorySegment`: explicit region and layout, scalar loop |
| `ArrayVectorDot` | `double[]` + Vector API: explicit SIMD lanes |
| `SegmentVectorDot` | `MemorySegment` + Vector API: explicit layout and explicit compute |

All variants fill their vectors from the same seed, so every `compute()`
runs over the same values in a different physical form. Two classes sit
beside the ladder: `ArrayLoadControl` reads the same bytes with the least
arithmetic the compiler allows, as a bandwidth control, and
`SegmentVectorUnalignedDot` is `SegmentVectorDot` with a different
alignment asked of the arena, for the alignment experiment.

## Requirements

JDK 25 (the Vector API is an incubator module) and Maven 3.8+ with a
JDK 25 toolchain entry.

```bash
mvn verify
java -jar target/benchmarks.jar
```

`mvn test` checks correctness: for the same size and seed the three scalar
variants produce bit-identical results, the Vector API variants are held to
a relative tolerance, since they merge one partial sum per lane and the
addition order differs by construction.

## Measuring

```bash
scripts/bench.sh m1                          # one run: -f 3 -wi 5 -i 5, txt + json in results/
scripts/bench.sh m1-gc -prof gc              # same, with the GC profiler
BENCH_PIN=8-15,24-31 scripts/session.sh final            # everything under one machine state
BENCH_PIN=8-15,24-31 scripts/experiment-alignment.sh     # alignment varied, nothing else
```

`bench.sh` refuses a dirty working tree, rebuilds the jar and stamps every
result file with the commit, JDK build and CPU. `session.sh` runs timings,
GC profile, one disassembly per variant and the hardware counters, writes a
manifest of the machine state as it actually was, and restores the machine
on exit. Disassembly needs an hsdis library, which the JDK does not ship;
`scripts/build-hsdis.sh` builds one from the JDK sources with the LLVM
backend. How to read the listings, and why the decoder matters, is in
`docs/reading-perfasm.md`.

## Where the numbers are

`docs/final-report.md` is the citable report: the five variants and the
control, measured together in one session. `results/README.md` explains
the split between the session, the experiments and the archived milestone
runs. Everything in `docs/archive/` and `results/archive/` is superseded
and kept as a record of how the investigation proceeded.

The figures are valid only for the machine, JDK build and ISA they were
measured on. Every ratio between variants depends on the CPU's SIMD
capabilities. Run the benchmarks on your own hardware instead of
transferring them.

## Reading

- [JEP 454: Foreign Function & Memory API](https://openjdk.org/jeps/454)
- [JEP 508: Vector API (Tenth Incubator)](https://openjdk.org/jeps/508)
- [JMH samples](https://github.com/openjdk/jmh/tree/master/jmh-samples)
- Ulrich Drepper, [*What Every Programmer Should Know About Memory*](https://akkadia.org/drepper/cpumemory.pdf)
- Denis Bakhvalov, [*Performance Analysis and Tuning on Modern CPUs*](https://easyperf.net)
- Aleksey Shipilëv, [*JVM Anatomy Quarks*](https://shipilev.net/jvm/anatomy-quarks/)
- [Agner Fog's instruction tables](https://agner.org/optimize/), [uops.info](https://uops.info), [felixcloutier.com/x86](https://www.felixcloutier.com/x86/)
