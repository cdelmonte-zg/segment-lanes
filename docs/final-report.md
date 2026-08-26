# Final session report: the five variants, measured together

The controlled session the whole lab was built for: one machine state, one
build, every file the article can quote. Run with `scripts/session.sh final`
on 2026-08-26, commit `78c2e2e`, JDK 25.0.4, Ryzen 9 7950X3D, Blackhole mode
`compiler`, pinned to `8-15,24-31`. The full state is in
`results/2026-08-26-final-manifest.txt`, which records the governor and perf
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
   `ValueLayout.JAVA_DOUBLE`. See `m4-report.md` for why.

The earlier reports stay as they are, as dated snapshots of what was true
when they were written. Two of their conclusions are corrected below.

Raw data: `results/2026-08-26-final-avgt.{txt,json}`,
`-gc-avgt.{txt,json}`, five `-perfasm-<variant>.txt` at size 1024, and
`-perfnorm-16777216.txt`. The ns/op inside the profiled files are not
citable: the profiler perturbs the run.

**Note on two of the perfasm files.** `arrayVector` and `arrayLoadControl`
each contain six instructions the disassembler failed to decode, showing up
as `.byte 0x62` (the EVEX prefix) followed by nonsense mnemonics. The decoder
is capstone 4.0.2, which predates most AVX-512 coverage and is the newest
version this distribution ships; hsdis here is built on the capstone backend,
so the same limit applies to it. Re-running does not help, and it is
deterministic: it affects the two kernels that load through
`DoubleVector.fromArray` under JDK 25.0.4, while the same variant under
25.0.3 (`2026-08-25-m3-perfasm-arrayvector.txt`) and the segment kernels are
clean. **Nothing in this report rests on those six instructions**: the
structural claim they would have supported is measured directly by the
instruction counters, see the perfnorm section.

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
   flips by 2.8 percent in the array's favour. The mechanism is the one from
   `m4-report.md` seen from the other side: the segment is aligned to the
   cache line by request, while the payload of a `double[]` starts 16 bytes
   past its object header and its 64-byte loads straddle two lines.
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
   experiment in `m4-report.md`), not yet for the array. Closing it would take
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

The additions are a strictly ordered dependency chain in all three. **C2
never reassociates floating-point sums**, not in the scalar loops and not in
the vector one: the only reassociation in the whole program is the one the
lanes declare.

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
   not by arithmetic. About 44 GB/s on one core.
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
4. **`segmentScalar` does four times the loads** (33.8 M against 8.5 M): one
   per element instead of one per 64-byte line. It carries 1.25 misses per
   line, the worst of the six, and is still faster than `array` at 16 M
   because it remains compute-bound and the misses hide behind the chain.

## Corrections to the earlier reports

- **`m4-report.md`, finding 1, "In cache, the segment is the array."** True
  for the kernel measured there, which took the 8-byte element alignment.
  With `allocate(bytes, 64)` the segment is 25 percent *faster* than the array
  at 1024. The earlier sentence describes the default, not the API.
- **`m3-report.md`, finding 1 and the Open section, the bandwidth
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
- **Explicit layout pays at every size.** Changing only the alignment moves
  `segmentVector` by 18 percent at 1024, 6.7 percent at 65 536 and 15.2 percent
  at 16 M. It is not the case that computation matters while compute-bound and
  layout takes over when memory-bound: in cache the layout is worth more, not
  less, and it is what decides whether the segment beats the array (by 25
  percent at 1024) or loses to it (by 2.8 percent at 16 M). **The two levers do
  not hand over to each other: the first fades as the data moves away, the
  second does not.**
- **Section 3**, on layout: `ValueLayout.JAVA_DOUBLE` promises the 8 bytes of
  the element and a 512-bit load wants 64, so the natural form of the API
  leaves double-digit percentages on the floor, invisibly while the data is in
  cache. With a `double[]` you cannot ask for an alignment at all. Say it with
  the right sign: the point is not that segments are faster, it is that the
  control exists and has to be exercised.
- **Section 3, second half**: the scalar segment loop is **not vectorized at
  all** and still matches the array, which is the more interesting version of
  "FFM costs nothing". It does not cost nothing: it costs the auto-vectorizer,
  and that cost is invisible because the wall in both is the ordered chain of
  additions. That is the step that sets up section 4.
- **Section 4**, on computation: the three-column instruction table, plus the
  fact that the compiler never reassociates floating-point sums. The
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
  CCD, and the JVM layout flags recorded in the manifest. The reasoning about
  lanes and alignment transfers across ISAs; the numbers do not.

## Open

- Two perfasm files carry six undecoded instructions each (capstone 4.0.2).
  Fixing it means building capstone 5 from source and relinking hsdis, about
  fifteen minutes in userspace. Not needed for this article. Worth doing
  before the next SIMD lab, where the disassembly carries more weight.
- The 1.6 percent `segmentScalar` advantage at 65 536 has been unexplained
  across four sessions and is now inside the error bars. Either profile at
  that size or keep it out of the article.
- `segmentVector` is 2.8 percent slower than `arrayVector` at 16 M, outside
  the error bars. The likely cause is the seven address instructions per
  iteration, but that is inference, not measurement.
