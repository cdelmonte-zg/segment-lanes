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

A sixth class, `ArrayLoadControl`, sits beside the ladder without being part of it: it walks the same two arrays with the least arithmetic the compiler will allow, as a control for whether the largest size is limited by memory bandwidth rather than by compute.

## Requirements and usage

JDK 25 (Vector API is an incubator module), Maven 3.8+ with a JDK 25 toolchain entry.

```bash
mvn verify
java -jar target/benchmarks.jar
```

## A note on the numbers

Benchmark results in this repo and in the article are valid only for the machine, JDK build, and ISA they were measured on. Vectorization behavior (and thus every ratio between variants) depends on the CPU's SIMD capabilities; do not transfer the ratios elsewhere. Run the benchmarks on your own hardware instead.

## Milestones

The lab was built one variant at a time. Result files in `results/` and the
reports in `docs/` are named after the milestone that produced them: one
variant per milestone, with that variant's disassembly inside the milestone
that introduced it.

| Milestone | What it added | Report |
|---|---|---|
| M1 | `BoxedListDot` and `PrimitiveArrayDot`, the two baselines | numbers in `results/` |
| M1.5 | disassembly of `PrimitiveArrayDot` | see M2's report |
| M2 | `SegmentScalarDot` | `docs/m2-report.md` |
| M3 | `ArrayVectorDot` | `docs/m3-report.md` |
| M4 | `SegmentVectorDot` | `docs/m4-report.md` |
| final | all five measured together, plus `ArrayLoadControl` | `docs/final-report.md` |

M1.5 is a half step because it added no variant. It disassembled the
primitive-array baseline and found it already partly auto-vectorized, which
reframed everything measured after it.

`ArrayLoadControl` is an experimental control rather than a sixth variant: it
reads the same bytes with the minimum arithmetic the compiler will allow, to
test whether the largest size is limited by bandwidth rather than by compute.

The `final` session is not a milestone either. It reruns everything under a
single machine state and its figures supersede M1 to M4, which stay in place
as dated snapshots. Read the warning at the top of `docs/final-report.md`
before comparing numbers across them.

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

To regenerate everything in one go, under a single machine state:

```bash
BENCH_PIN=8-15,24-31 scripts/session.sh final
```

That runs timings, the GC profile, one disassembly per variant and the
hardware counters, and writes a manifest recording the governor and perf
settings as they actually were, not as they were meant to be. It restores the
machine from a trap on exit, so an interrupted session cannot leave the
governor pinned. Note that everything inside a session runs under
`bench-system setup`, which makes its numbers a self-consistent baseline of
their own rather than a continuation of runs taken on a stock machine.

Correctness is checked separately from measurement, with two levels of
guarantee. For the same size and seed, `mvn test` asserts that the scalar
variants (`BoxedListDot`, `PrimitiveArrayDot`, `SegmentScalarDot`) produce
bit-identical results: they all add the products in index order, and C2
preserves that order. The Vector API variants are held to a relative
tolerance instead. They accumulate one partial sum per lane and merge the
lanes at the end, so the addition order differs by construction and the
last bits legitimately diverge (at size 1024, seed 42: `263.7864929552308`
scalar vs `263.78649295523115` vectorized, about 6 ulp). Neither value is
"the right one"; the scalar loop fixes the order, the vectorized loop gives
it up explicitly, which is what makes the lanes possible.

### Assembly verification

Hot-loop disassembly runs need perf counters unlocked and an hsdis library,
which is not shipped with the JDK. JDK 25 requires one that exports
`decode_instructions_virtual`, so older distribution packages exporting the
previous ABI will not do; build one against either the capstone or the
binutils backend from `src/utils/hsdis` in the JDK sources.

```bash
scripts/bench-system.sh setup    # governor + perf counters; `restore` when done
LD_LIBRARY_PATH=<path-to-hsdis-dir> \
java -jar target/benchmarks.jar 'DotProductBench.array$' -p size=1024 \
-f 1 -wi 5 -i 5 -prof perfasm | tee results/<date>-<tag>-perfasm.txt
```

Know your decoder before trusting a listing. The runs in this repo used a
capstone-backed hsdis, and capstone 4.0.2 does not decode every AVX-512
encoding: undecoded instructions appear as `.byte 0x62`, the EVEX prefix,
followed by mnemonics that belong to no real instruction. Two of the five
disassemblies in the final session are affected, in a reproducible way, and
say so in `docs/final-report.md`. If a listing looks structurally wrong,
suspect the decoder before the compiler, and cross-check against the
instruction counters from `-prof perfnorm`, which do not depend on it.

See docs/reading-perfasm.md for how to read the output.

## Reading

Specifications this lab builds on:

- [JEP 454: Foreign Function & Memory API](https://openjdk.org/jeps/454) — final in JDK 22; `MemorySegment`, `Arena`, `ValueLayout`.
- [JEP 508: Vector API (Tenth Incubator)](https://openjdk.org/jeps/508) — the incubation status shipped with JDK 25.
- [JMH samples](https://github.com/openjdk/jmh/tree/master/jmh-samples) — the canonical benchmark-methodology walkthrough.

Background on the mechanisms the measurements expose:

- Ulrich Drepper, [*What Every Programmer Should Know About Memory*](https://akkadia.org/drepper/cpumemory.pdf) (2007) — cache hierarchy, locality, prefetching; sections 1–3 and 6 cover everything this lab touches.
- Denis Bakhvalov, [*Performance Analysis and Tuning on Modern CPUs*](https://easyperf.net) — modern measurement practice, SIMD, reading profiles.
- Aleksey Shipilëv, [*JVM Anatomy Quarks*](https://shipilev.net/jvm/anatomy-quarks/) — what HotSpot actually does, in digestible pieces.
- [Agner Fog's instruction tables](https://agner.org/optimize/) and [uops.info](https://uops.info) — per-microarchitecture instruction latency and throughput.
- [felixcloutier.com/x86](https://www.felixcloutier.com/x86/) — per-instruction x86 reference.