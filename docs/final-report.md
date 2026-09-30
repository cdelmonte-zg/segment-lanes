# Final session report: the five variants, measured together

The controlled session the whole lab was built for: one machine state, one
build, every file the article can quote. Run with `scripts/session.sh final`
on 2026-08-26, commit `bf5a096`, JDK 25.0.4, Ryzen 9 7950X3D, Blackhole mode
`compiler`, pinned to `8-15,24-31`. The full state is in
`results/session/2026-08-26-final-manifest.txt`, which records the governor and perf
settings as measured rather than as intended.

## Read this before comparing with M1 to M4

**These numbers supersede the earlier reports and are not comparable to them
line by line.** Three things changed at once, all of them deliberate:

1. **Machine state.** M1 to M4 ran stock (governor powersave, no pinning);
   this session runs under `bench-system setup` (governor performance) pinned
   to one CCD. Better conditions, different baseline.
2. **One `@State` per variant.** The old shared state built all five variants
   on every fork, so their placement in memory depended on the allocation
   order: exactly the thing these measurements turned out to be sensitive to.
   It also tightens the numbers.
3. **Cache-line alignment.** Both segment variants now allocate with
   `arena.allocate(bytes, 64)` instead of taking the element alignment of
   `ValueLayout.JAVA_DOUBLE`. See `archive/m4-report.md` for why.

The earlier reports stay as they are, as dated snapshots of what was true
when they were written. Two of their conclusions are corrected below.

Raw data: `results/session/2026-08-26-final-avgt.{txt,json}`,
`-gc-avgt.{txt,json}`, five `-perfasm-<variant>.txt` at size 1024, and
`-perfnorm-16777216.txt`. The ns/op inside the profiled files are not
citable: the profiler perturbs the run.

**A note on the disassembler.** All five listings here decode completely,
because this session used an LLVM-backed hsdis built by `scripts/build-hsdis.sh`.
An earlier session used a capstone-backed one, and capstone 4.0.2, the newest
this distribution ships, does not cover every AVX-512 encoding: six
instructions per file came out as `.byte 0x62`, the EVEX prefix, followed by
mnemonics belonging to no real instruction, deterministically in the two
kernels that load through `DoubleVector.fromArray`.

That is worth recording because reading such a listing literally leads to a
wrong conclusion: it showed `arrayVector` with a different instruction mix from
`segmentVector`, when with a working decoder the two are identical. Suspect the
decoder before the compiler, and cross-check against instruction counters,
which do not depend on one.

## Numbers (avgt, 3 forks x 5 iterations)

| size | list | array | segmentScalar | arrayVector | segmentVector | arrayLoadControl |
|---|---|---|---|---|---|---|
| 1 024 | 599.5 ± 9.1 ns | 540.0 ± 4.0 ns | 530.8 ± 4.5 ns | 76.0 ± 7.2 ns | **60.6 ± 1.0 ns** | 77.2 ± 6.8 ns |
| 65 536 | 39.28 ± 0.46 µs | 36.60 ± 0.36 µs | 35.37 ± 0.43 µs | 7.82 ± 0.21 µs | **7.04 ± 0.22 µs** | 7.53 ± 0.20 µs |
| 16 M | 29.04 ± 0.56 ms | 10.13 ± 0.15 ms | 9.87 ± 0.06 ms | 6.19 ± 0.05 ms | 6.29 ± 0.07 ms | **6.11 ± 0.02 ms** |

Each vector variant against its own scalar baseline:

| size | array / arrayVector | segmentScalar / segmentVector |
|---|---|---|
| 1 024 | 7.1x | **8.8x** |
| 65 536 | 4.7x | 5.0x |
| 16 M | 1.6x | 1.6x |

Zero steady-state allocation everywhere: `gc.count ≈ 0` and `gc.alloc.rate`
flat at the 0.007 MB/s harness background for all six benchmarks at every
size. Per operation that background reads as 0.004 B/op at 1024 and grows
with the duration of the operation, up to 195 B/op for `list` at 16 M, which
is 0.007 MB/s times 29 ms: the same constant rate, not allocation inside the
kernel. That is no measurable steady-state allocation, which is what the
profiler can show; it is consistent with vectors staying in registers and
segments not escaping, without proving either directly.

## Findings

1. **The segment beats the array in cache, and the margin is wide.**
   60.6 against 76.0 ns at 1024, a 20 percent difference; 7.04 against 7.82 µs
   at 65 536, still separated. At 16 M the order flips by 1.7 percent in the
   array's favour. **The mechanism is not the
   alignment**, which a later single-variable experiment ruled out at these
   sizes (see below): an unaligned segment beats the array in cache just as
   well, 60.1 against 69.4 ns comparing like-for-like fork modes. Both vector
   kernels compile to the same loop body, and the segment executes more
   instructions while finishing sooner, so the difference is throughput rather
   than instruction count. Where that throughput goes is not established.
2. **The two kernels that read from `double[]` are also the least
   reproducible.** At 1024, `arrayVector` carries ±7.2 ns on 76 (9.5 percent)
   and `arrayLoadControl` ±6.8 on 77 (8.9 percent), while `segmentVector`
   carries ±1.0 on 60.6 (1.7 percent) and the scalar variants stay under 1.6.
   This is not dispersion around a mean: the dedicated experiment (below) shows
   `arrayVector` to be bimodal, a fast plateau plus occasional slow forks.
   `arrayLoadControl` carries a comparable spread here but was not part of that
   experiment, so its shape is unmeasured.
   **The spread is consistent with heap-placement effects, and the segment
   avoids that source of variation by requesting its alignment explicitly.**
   The causal link is demonstrated for the segment, not for the array; closing
   it would take a padded-array variant sweeping offsets 0 to 7.
3. **The speedup over the scalar baseline is larger for the segment (8.8x)
   than for the array (7.1x) at 1024, and the surplus does not come from the
   baseline.** In time the two scalar baselines are equal: 530.8 against
   540.0 ns, the segment's even 1.7 percent faster, although it is purely
   scalar while `array` is partly auto-vectorized. The code shapes differ,
   the times do not, and a ratio divides by the time. The whole gap sits in
   the vectorized times, 60.6 against 76.0 ns: 530.8 / 76.0 = 7.0x. So the
   8.8x is finding 1, the unexplained in-cache advantage of `segmentVector`,
   seen through a ratio. Quote it with that attached or not at all.
   (Corrected 2026-09-07 after review: the earlier text attributed the
   surplus to a "weaker baseline", which the numbers contradict.)
4. **The ratio still collapses with the working set**, 7.1x to 4.7x to 1.6x,
   and section 6 of the article is that collapse: the wall moves from the
   order of the additions to the arrival of the data.
5. `list` at 16 M is 29.0 ms, 2.9x the primitive array, stable across every
   session so far.
6. `segmentScalar` is 3.4 percent faster than `array` at 65 536 (35.37 against
   36.60 µs) and 2.5 percent at 16 M. This is the same unexplained margin as in
   M2, and unlike in the previous session it is now outside the error bars
   again. Four sessions without a mechanism: **still not to be quoted.**

## Perfasm at 1024: three ways to add the same numbers

Instruction mix inside the hot loop, one file per variant. This is the table
for section 4 of the article.

| | segmentScalar | array (auto-vectorized) | arrayVector | segmentVector |
|---|---|---|---|---|
| explicit loads | 8x `vmovsd` | 8x `vmovups` zmm | 8x `vmovups` zmm | 8x `vmovups` zmm |
| multiplies | 8x `vmulsd` | 8x `vmulpd` zmm | 8x `vmulpd` zmm | 8x `vmulpd` zmm |
| lane extraction | none | 32x `vpshufd`, 16x `vextractf128`, 8x `vextracti64x4` | none | none |
| additions | 8x `vaddsd` | **64x `vaddsd`** | 8x `vaddpd` | 8x `vaddpd` |

Every remaining load is folded into a multiply as a memory operand, so the
explicit-load row understates the traffic by half in the vector columns. The
`array` column is read from a hot region that perfasm prints truncated: seven
chunks are visible, at displacements 0x10 through 0x190, but the loop advances
by 64 elements (`leal 0x40`) and 64 `vaddsd` follow, so the eighth chunk exists
outside the printed window. **All 64 elements are multiplied packed**, and all
64 are then fed back through a scalar chain to preserve the order.
`arrayLoadControl`, not shown, compiles to 16 `vaddpd` with every load folded
and no multiply left, which is what it was written to test.

**The two vector columns are identical**, verified on both sides in this
session. That is the claim of section 4: explicit layout and explicit compute
produce the same loop body, differing only in the address arithmetic the
counters measure below.

Three readings, in order of how surprising they are:

1. **The scalar segment loop is not vectorized at all** (`vmovsd`, `vmulsd`,
   `vaddsd`, no packed instruction anywhere) and is still as fast as the
   array at 1024 and 65 536, and faster at 16 M. The wall is the ordered
   chain of additions in both, not the memory abstraction.
2. **The auto-vectorized array pays for the round trip.** C2 multiplies eight
   at a time in zmm registers, then spends 56 shuffle and extract
   instructions taking the lanes back apart to add them one by one, in order.
   It vectorizes as far as the semantics allow and no further.
3. **The declared version simply does not have that staircase.** Eight
   `vaddpd` per 64 elements instead of 64 `vaddsd`, and nothing between the
   multiply and the add. The reduction happens once, after the loop.

The additions are a strictly ordered dependency chain in all three. **C2 does
not reassociate the floating-point sums in these kernels**, neither in the
scalar loops nor in the vector one: the only reassociation in this program is
the one the lanes declare.

## Perfnorm at 16 M: the wall is the data

Counters per operation. Caveat: these come from `Cnt 3` (one sample per
fork), so the error bars are wide, up to ±32 percent on `cycles` for
`segmentVector`. The large ratios below are robust; the CPI values are
indicative.

| | array | segmentScalar | arrayVector | segmentVector | arrayLoadControl |
|---|---|---|---|---|---|
| ns/op | 10 153 792 | 9 971 554 | 6 115 252 | 6 199 044 | 6 166 993 |
| instructions | 36.98 M | 71.88 M | 7.23 M | 9.10 M | **5.11 M** |
| CPI | 1.50 | 0.78 | 4.73 | 3.82 | **6.69** |
| L1-dcache-load-misses | 4.20 M | 5.26 M | 4.26 M | 4.27 M | 4.25 M |
| L1-miss events per line | 1.002 | 1.254 | 1.016 | 1.018 | 1.012 |

The streaming minimum is 268 435 456 bytes over 64-byte lines = 4 194 304.

Read that last row as **events, not lines refetched**. The load count itself
shows why: 262 144 iterations of 16 architectural loads is 4.19 M, while the
counter reports 8.5 M, consistent with 64-byte accesses being served over the
256-bit datapath of this microarchitecture. So a ratio above 1.0 means extra
miss events per line, which misaligned accesses produce; it does not license
the statement that a third of the lines were fetched twice. The strong result
is the change itself: correcting the alignment moves the unaligned segment from
1.33 to 1.02 and nothing else moves with it. The 1.25 in the table belongs to
`segmentScalar`, a different kernel and a different cause. Closing the mechanism properly would take
the misaligned-access events this vendor exposes through IBS.

1. **The bandwidth claim, open since M3, is now measured.**
   `arrayLoadControl` walks the same two arrays with two independent
   accumulators and no multiply. It executes **29 percent fewer instructions**
   than `arrayVector` (5.11 M against 7.23 M) and has the **highest CPI of any
   variant** (6.69). In the avgt run, which is the citable one, it finishes at
   6.112 ± 0.021 ms against 6.188 ± 0.053: **29 percent fewer instructions buys
   1.2 percent of time**. That is the sharper way to put it than "the same
   duration", and it says what section 6 needs: at 16 M arithmetic is no longer
   the dominant constraint. About 44 GB/s for the single benchmark thread.
2. **Every vector variant streams ideally**, 1.00 to 1.02 L1-miss events
   per theoretical cache line.
   The excess that M4 found in the unaligned segment (1.33) is gone.
3. **The structural claim of section 4, twice over.** `segmentVector` executes
   9.10 M instructions against 7.23 M for `arrayVector`: 1.87 M more over
   262 144 iterations is **7.1 instructions per iteration**, exactly the address
   preamble in the listing. The disassembly and the counters now agree, and
   neither depends on the other.
4. **`segmentScalar` carries the highest miss-event rate of the six**, 1.25
   per line against 1.00 to 1.02, consistent with scalar 8-byte accesses rather
   than wide vector ones. As with every counter here, this is a hardware event
   count and not a one-to-one count of source-level loads. It is
   still faster than `array` at 16 M because it remains compute-bound and the
   misses hide behind the chain.

## The alignment experiment, by size

`archive/m4-report.md` established at 16 M that segment alignment is causal, by
changing that one property and nothing else. The obvious follow-up was whether
the same holds in cache, where the final session shows the segment ahead of the
array. Branch `experiment/alignment-vs-size` answers it properly:
`SegmentVectorUnalignedDot` is `SegmentVectorDot` with one line changed: it
asks the arena for the layout's natural 8-byte alignment instead of 64. Note
that this is a *request*, a lower bound: an allocation may happen to come back
more strongly aligned. Both variants run **in the same JMH session**, so alignment is the single variable
rather than a comparison across two runs. Pinned to one CCD under
`bench-system setup`, ten forks, with `arrayVector` present as a reference.

| size | natural (8-byte request) | 64-byte request | effect |
|---|---|---|---|
| 1 024 | 63.01 ± 4.28 ns | 60.88 ± 0.56 ns | -3.4%, **error bars overlap** |
| 65 536 | 7 221 ± 56 ns | 7 190 ± 72 ns | -0.4%, **error bars overlap** |
| 16 M | 7 385 ± 18 µs | 6 186 ± 38 µs | **-16.2%, separated** |

Three results, and two of them correct this report.

1. **At 16 M the effect holds and is larger under control**, 16.2 percent
   against the 15 percent measured across two runs. It was not CCD migration
   and not frequency scaling.
2. **In cache alignment does nothing measurable.** This is a conclusive
   negative rather than an inconclusive one: the aligned variant carries ±0.9
   percent, so an effect of the size seen at 16 M could not hide. An earlier
   stock-machine version of this experiment suggested -13 percent at 1024; most
   of that was machine noise, which is what running it without pinning bought.
3. **The in-cache advantage of the segment is therefore unexplained.** At 1024
   the *unaligned* segment still beats `arrayVector`. Whatever the segment is
   doing better in cache, it is not the alignment.

### At 1024 the averages hide two modes

The mean is the wrong statistic here. Per-fork averages, ten forks:

```
arrayVector              69.2 69.2 69.3 69.3 69.7 69.7 69.9 70.8 | 82.7 82.7
segmentVector (64B)      59.8 60.0 60.1 60.1 60.4 60.9 60.9 61.3 62.2 63.1
segmentVectorUnaligned   59.9 60.1 60.1 60.1 60.1 60.2 60.3 60.3 60.4 | 88.7
```

The 63.01 ns quoted above for the unaligned variant describes **no fork that
actually ran**: nine sit at 60.1 and one at 88.7. Three consequences:

- **In the fast mode the two segment variants are indistinguishable**, 60.1
  against 60.5. At this size alignment does not change the time, it changes how
  often a run lands in the slow mode: one in ten against zero in ten. That is
  far too thin to state as a rule, and it is reported here as an observation.
- **The array is bimodal too**, two forks in ten at 82.7 against eight at 69.4,
  so this is not a property of segments.
- **The segment's in-cache advantage survives the correction and gets
  cleaner**: 60.1 against 69.4 comparing fast modes, about 13 percent, with no
  dependence on alignment or on the outliers.

The profiled runs sample this distribution differently, which is a reason to
distrust their absolute numbers beyond the usual one. Under `perfnorm` the
unaligned variant lands at 72.7, 75.4 and 75.7 across its three forks, that is
the slow mode every time, while the clean run finds it nine times out of ten in
the fast one. What those profiled runs do establish is the character of the slow
mode: same instruction count (742 against 743), same L1 load events, more
cycles. Whatever it is, it is not extra work.

## Corrections to the earlier reports

- **`archive/m4-report.md`, finding 1, "In cache, the segment is the array."**
  Superseded, but not for the reason it first looked: the segment is ahead of
  the array in cache, and the by-size experiment shows that alignment is not
  what puts it there. An unaligned segment leads by the same margin.
- **`archive/m3-report.md`, finding 1 and the Open section, the bandwidth
  hypothesis.** No longer a hypothesis, see above. The load-only control
  kernel listed there as optional turned out to be the measurement that
  closes it.
- Every quoted figure in M1 to M4 is superseded by the tables above.

## Direction for the article

The lab does not say that the Vector API and `MemorySegment` are faster. It
says that the JVM now lets a program make explicit two things the object model
kept implicit: **the layout of the data and the structure of the computation**.
The measurements then show what each one buys, and they do not buy the same
thing in the same place.

- **Explicit computation pays while the data is near.** 7.1x in L1, 4.7x at
  1 MB, 1.6x at 256 MB. The ceiling is not arithmetic: at 16 M a kernel doing
  29 percent fewer instructions takes the same time. Section 4 gets the
  speedup, section 6 gets its collapse.
- **Explicit layout is a performance variable the program controls, and it
  pays where the memory system is the constraint.** The single-variable
  experiment now covers every size: changing only the segment alignment is
  worth 16.2 percent at 16 M and nothing measurable at 1024 and 65 536. That
  shape is the mechanism itself, since cache lines matter when you are crossing
  them in bulk. For section 3 the claim is therefore precise and modest:
  **alignment has become an explicit parameter of the memory model**, and it
  buys double digits exactly where the data no longer fits. What it does not
  explain is why the segment is ahead of the array in cache, which the same
  experiment rules out as an alignment effect and leaves open. **For the
  article, use the 16 M result and stop there.** The bimodality at 1024 is
  worth keeping in the lab, but in a section on layout it would derail the
  argument for a phenomenon whose cause is unidentified.
- **Section 3**, on layout: `ValueLayout.JAVA_DOUBLE` requires only the
  natural 8-byte alignment of a `double`, while the program can request a
  stronger one for a wider access pattern. In the controlled 16 M experiment,
  asking for 64 bytes removes a double-digit penalty. The property that matters
  is not that 64 bytes are universally better, but that **alignment has become
  an explicit parameter of the memory model**. With a `double[]` you cannot ask
  at all. Say it with the right sign: the point is not that segments are
  faster, it is that the control exists and has to be exercised.
- **Section 3, second half**: the scalar segment loop is **not vectorized at
  all** and still matches the array, which is the more interesting version of
  "FFM costs nothing". In this kernel on JDK 25.0.4, C2 does not auto-vectorize
  the scalar `MemorySegment` loop, and that costs nothing measurable because
  the wall in both is the ordered chain of additions. That is the step that sets up section 4.
- **Section 4**, on computation: the three-column instruction table, plus the
  fact that the compiler did not reassociate the floating-point sums in any of
  the three kernels here. The
  programmer declares the structure, one partial sum per lane, and **gives up
  the sequential order explicitly**; the compiler cannot infer that from the
  scalar loop, which is why it has to build the extract-and-add staircase.
- **Quote every ratio with what it divides.** `segmentScalar / segmentVector`
  is 8.8x against 7.1x for the array pair, which reads as if the segment
  kernel were better. The scalar baselines are equal in time (530.8 against
  540.0 ns), so the surplus is not a baseline artefact: it is the 20 percent
  in-cache advantage of `segmentVector` over `arrayVector`, whose cause is
  open. Say that, or quote only the direct comparison.
- **`List<Double>` is the size-dependent one**: 1.11x at 1024, 1.07x at 65 536,
  then 2.87x at 16 M. Boxing and pointer chasing become visible exactly when the
  memory hierarchy starts to dominate.
- **Scoping, as always**: this kernel, JDK 25.0.4, Zen 4, 512-bit species, one
  CCD. The reasoning about lanes and alignment transfers across ISAs; the
  numbers do not, and the JVM layout flags this run depended on are recorded in
  the manifest rather than assumed.

## Open

- The slow mode at 1024 is uncharacterized. One precise measurement would
  close it: log `segment.address() & 63` for both segments on every fork and
  correlate it with that fork's time. If the 88.7 ns fork sits at a particular
  offset and the 60 ns ones elsewhere, the mechanism is settled. Not needed for
  the article.
- Why the segment is ahead of the array in cache is open. Same loop body, more
  instructions, better CPI: the time goes somewhere the counters at 1024 are
  too noisy to show, and the alignment experiment has ruled out the obvious
  candidate. The remaining hypothesis is the placement of the two `double[]`
  in a 32 KB L1, which would need a padded-array variant sweeping offsets to
  test.
- The `segmentScalar` advantage over `array` has been unexplained across every
  session: 3.4 percent at 65 536 and 2.5 percent at 16 M here, outside the error
  bars, inside them in the previous session. Either profile at those sizes or
  keep it out of the article.
- `segmentVector` trails `arrayVector` at 16 M by 1.7 percent in this session,
  with intervals that just touch, and by 1.3 percent in the ten-fork experiment,
  where they separate. The seven address instructions per iteration are the
  obvious candidate, but that is inference, not measurement, and the margin is
  too small to build anything on.
