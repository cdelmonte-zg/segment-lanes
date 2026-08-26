# Final session report: the five variants, measured together

The controlled session the whole lab was built for: one machine state, one
build, every file the article can quote. Run with `scripts/session.sh final`
on 2026-08-26, commit `78c2e2e`, JDK 25.0.4, Ryzen 9 7950X3D, Blackhole mode
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

**Note on two of the perfasm files.** `arrayVector` and `arrayLoadControl`
each contain six instructions the disassembler failed to decode, showing up as
`.byte 0x62` (the EVEX prefix) followed by mnemonics that belong to no real
instruction. The cause is the decoder, not the compiler: these runs used a
capstone-backed hsdis, and capstone 4.0.2, the newest this distribution ships,
does not cover every AVX-512 encoding. It is deterministic, affecting the two
kernels that load through `DoubleVector.fromArray` under JDK 25.0.4.

Re-taking the same disassembly with an LLVM-backed hsdis (`scripts/build-hsdis.sh`)
decodes everything, and the result matters: `arrayVector` then shows 8
`vmovups`, 8 `vmulpd` and 8 `vaddpd`, that is **the same loop body as
`segmentVector`**. Read literally, the capstone listing would have said the two
compile differently. Nothing in this report rests on those six instructions,
and the structural claim is in any case carried by the instruction counters,
which do not depend on a decoder.

## Numbers (avgt, 3 forks x 5 iterations)

| size | list | array | segmentScalar | arrayVector | segmentVector | arrayLoadControl |
|---|---|---|---|---|---|---|
| 1 024 | 590.2 ± 2.8 ns | 536.6 ± 3.7 ns | 541.4 ± 8.0 ns | 81.0 ± 6.5 ns | **60.4 ± 1.0 ns** | 72.7 ± 1.9 ns |
| 65 536 | 39.81 ± 0.72 µs | 36.21 ± 0.40 µs | 35.64 ± 0.44 µs | 7.93 ± 0.24 µs | **7.08 ± 0.25 µs** | 7.70 ± 0.21 µs |
| 16 M | 28.90 ± 0.54 ms | 10.45 ± 0.17 ms | 10.08 ± 0.20 ms | **6.08 ± 0.06 ms** | 6.26 ± 0.05 ms | 6.11 ± 0.06 ms |

Each vector variant against its own scalar baseline:

| size | array / arrayVector | segmentScalar / segmentVector |
|---|---|---|
| 1 024 | 6.6x | **9.0x** |
| 65 536 | 4.6x | 5.0x |
| 16 M | 1.7x | 1.6x |

Zero steady-state allocation everywhere: `gc.count ≈ 0` and `gc.alloc.rate`
flat at the 0.007 MB/s harness background for all six benchmarks at every
size, with `alloc.rate.norm` at or below 0.004 B/op. No vector is
materialized, no segment escapes.

## Findings

1. **The aligned segment beats the array in cache, and the margin is wide.**
   60.4 against 81.0 ns at 1024, a 25 percent difference with error bars far
   apart; 7.08 against 7.93 µs at 65 536, still separated. At 16 M the order
   flips by 2.8 percent in the array's favour. **The mechanism is not the
   alignment**, which a later single-variable experiment ruled out at these
   sizes (see below): an unaligned segment beats the array in cache just as
   well, 60.1 against 69.4 ns comparing like-for-like fork modes. Both vector
   kernels compile to the same loop body, and the segment executes more
   instructions while finishing sooner, so the difference is throughput rather
   than instruction count. Where that throughput goes is not established.
2. **The array is also less reproducible, and that is a finding in itself.**
   At 1024, `arrayVector` carries ±6.5 ns on 81 (8 percent) while
   `segmentVector` carries ±1.0 on 60 (1.6 percent). The others sit between
   0.5 and 2.6 percent, so `arrayVector` is not merely the worst: it is three
   times worse than the next one. The three forks are not noise around a mean
   but three plateaus, roughly 85.6, 72.8 and 84.7 ns, which is what different
   heap placements would look like. **The fork-to-fork spread is consistent
   with heap-placement effects; the segment removes that source of uncertainty
   by requesting its alignment explicitly.** Stated that way it is what the
   data supports: the causal link is demonstrated for the segment (see the
   experiment in `archive/m4-report.md`), not yet for the array. Closing it would take
   a padded-array variant sweeping offsets 0 to 7.
3. **The speedup over the scalar baseline is larger for the segment (9.0x)
   than for the array (6.6x) at 1024, and it is not because the vector kernel
   is better.** It is because the baselines differ: `segmentScalar` is purely
   scalar (see the disassembly below), while `array` is partly
   auto-vectorized. The ratio measures the distance from its own starting
   point, so quote it with the baseline attached or not at all.
4. **The ratio still collapses with the working set**, 6.6x to 4.6x to 1.7x,
   and section 6 of the article is that collapse: the wall moves from the
   order of the additions to the arrival of the data.
5. `list` at 16 M is 28.9 ms, 2.8x the primitive array, stable across all
   five sessions.
6. `segmentScalar` is 1.6 percent faster than `array` at 65 536 (35.64
   against 36.21 µs). This is the same unexplained margin as in M2, now
   inside the error bars. **Still not to be quoted.**

## Perfasm at 1024: three ways to add the same numbers

Instruction mix inside the hot loop, one file per variant. This is the table
for section 4 of the article.

| | segmentScalar | array (auto-vectorized) | segmentVector (declared) |
|---|---|---|---|
| explicit load instructions | 8x `vmovsd` | 6x `vmovups` zmm | 8x `vmovups` zmm |
| loads folded into the multiply | none | 7 memory operands | 8 memory operands |
| multiplies | 8x `vmulsd` | 7x `vmulpd` zmm | 8x `vmulpd` zmm |
| lane extraction | none | 32x `vpshufd`, 16x `vextractf128`, 8x `vextracti64x4` | none |
| additions | 8x `vaddsd`, ordered chain | **64x `vaddsd`**, ordered chain | 8x `vaddpd`, ordered chain |
| hot region share | 94.3% | 88.2% | 79.3% |

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
| ns/op | 10 292 554 | 10 190 575 | 6 153 034 | 6 458 250 | 6 117 452 |
| instructions | 36.96 M | 71.79 M | 7.22 M | 9.09 M | **5.11 M** |
| CPI | 1.52 | 0.78 | 4.71 | 3.81 | **6.58** |
| L1-dcache-loads | 8.56 M | 33.83 M | 8.48 M | 8.56 M | 8.48 M |
| L1-dcache-load-misses | 4.21 M | 5.25 M | 4.26 M | 4.27 M | 4.25 M |
| L1-miss events per line | 1.003 | 1.253 | 1.016 | 1.017 | 1.013 |

The streaming minimum is 268 435 456 bytes over 64-byte lines = 4 194 304.

Read that last row as **events, not lines refetched**. The load count itself
shows why: 262 144 iterations of 16 architectural loads is 4.19 M, while the
counter reports 8.5 M, consistent with 64-byte accesses being served over the
256-bit datapath of this microarchitecture. So a ratio above 1.0 means extra
miss events per line, which misaligned accesses produce; it does not license
the statement that a third of the lines were fetched twice. The strong result
is the change itself: correcting the alignment moves the segment from 1.25 to
1.02 and nothing else moves with it. Closing the mechanism properly would take
the misaligned-access events this vendor exposes through IBS.

1. **The bandwidth claim, open since M3, is now measured.**
   `arrayLoadControl` walks the same two arrays with two independent
   accumulators and no multiply. It executes **29 percent fewer instructions**
   than `arrayVector` (5.11 M against 7.22 M) and has the **highest CPI of any
   variant** (6.58), and it finishes in the same time. In the avgt run, which
   is the citable one: 6.107 ± 0.055 ms against 6.084 ± 0.064. Less work, same
   duration. At 16 M the dot product is limited by the arrival of the data,
   not by arithmetic. About 44 GB/s for the single benchmark thread.
2. **Every vector variant streams ideally**, 1.00 to 1.02 L1-miss events
   per theoretical cache line.
   The excess that M4 found in the unaligned segment (1.33) is gone.
3. **The structural claim of section 4, without needing the disassembly.**
   `segmentVector` executes 9.09 M instructions against 7.22 M for
   `arrayVector`: 1.87 M more over 262 144 iterations is **7.1 instructions
   per iteration**, exactly the address preamble visible in the (clean)
   `segmentVector` listing. Explicit layout and explicit compute compile to
   the same loop plus one address computation, and this is a hardware count,
   independent of what the disassembler managed to render.
4. **`segmentScalar` records about four times as many L1 load events** as the
   vector kernels (33.8 M against 8.5 M), consistent with scalar 8-byte
   accesses rather than wide vector ones. As with the miss counter, this is a
   hardware event count and not a one-to-one count of source-level loads. It
   also carries the highest miss-event rate of the six, 1.25 per line, and is
   still faster than `array` at 16 M because it remains compute-bound and the
   misses hide behind the chain.

## The alignment experiment, by size

`archive/m4-report.md` established at 16 M that segment alignment is causal, by
changing that one property and nothing else. The obvious follow-up was whether
the same holds in cache, where the final session shows the segment ahead of the
array. Branch `experiment/alignment-vs-size` answers it properly:
`SegmentVectorUnalignedDot` is `SegmentVectorDot` with one line changed, and
both run **in the same JMH session**, so alignment is the single variable
rather than a comparison across two runs. Pinned to one CCD under
`bench-system setup`, ten forks, with `arrayVector` present as a reference.

| size | 8-byte alignment | 64-byte alignment | effect |
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

- **`archive/m4-report.md`, finding 1, "In cache, the segment is the array."** True
  for the kernel measured there, which took the 8-byte element alignment.
  With `allocate(bytes, 64)` the segment is 25 percent *faster* than the array
  at 1024. The earlier sentence describes the default, not the API.
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

- **Explicit computation pays while the data is near.** 6.6x in L1, 4.6x at
  1 MB, 1.7x at 256 MB. The ceiling is not arithmetic: at 16 M a kernel doing
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
- **Quote every ratio with its baseline.** `segmentScalar / segmentVector` is
  9.0x against 6.6x for the array pair, which reads as if the segment kernel
  were better. It is not: the direct comparison is 25 percent, and the 9.0x is
  inflated by a worse starting point, the unvectorized scalar segment loop. A
  ratio measures distance from its own baseline, and these baselines differ.
- **`List<Double>` is the size-dependent one**: 1.10x at 1024 and 65 536, then
  2.76x at 16 M. Boxing and pointer chasing become visible exactly when the
  memory hierarchy starts to dominate.
- **Scoping, as always**: this kernel, JDK 25.0.4, Zen 4, 512-bit species, one
  CCD. The reasoning about lanes and alignment transfers across ISAs; the
  numbers do not. Note that this session's manifest predates the change that
  records the JVM layout flags, so any statement about array headers here rests
  on the defaults of this JDK build rather than on a logged value.

## Open

- Two perfasm files in this session carry six undecoded instructions each.
  Fixed for the future rather than in place: `scripts/build-hsdis.sh` now
  builds an LLVM-backed hsdis, which decodes them all. Re-taking those two
  listings would cost two minutes and a machine setup.
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
- This session's manifest predates the flag logging added to `session.sh`.
  A rerun would produce a manifest that records `ObjectAlignmentInBytes`,
  `UseCompactObjectHeaders` and the rest, making the layout statements
  auditable rather than dependent on the defaults holding.
- The 1.6 percent `segmentScalar` advantage at 65 536 has been unexplained
  across four sessions and is now inside the error bars. Either profile at
  that size or keep it out of the article.
- `segmentVector` is 2.8 percent slower than `arrayVector` at 16 M, outside
  the error bars. The likely cause is the seven address instructions per
  iteration, but that is inference, not measurement.
