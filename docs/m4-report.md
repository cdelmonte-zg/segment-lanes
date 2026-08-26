# M4 report: Vector API on MemorySegment

Reading of the M4 measurements for `SegmentVectorDot` (2026-08-26): the
fifth and last variant of the ladder, and the one the whole lab was built
to reach. Commit `b3a68b3` for the kernel, `b063f69` for the raw data.
Ryzen 9 7950X3D, Blackhole mode `compiler`, timing runs on a stock
machine (governor powersave, no pinning) as in M1/M2/M3.

**One condition did change: the JDK.** M1, M2 and M3 ran on 25.0.3+9;
this run is on **25.0.4+7**, a system update between the 25th and the
26th. The shared variants reproduce within about 1 to 6 percent of their
previous values (see "Reproducibility" below), so cross milestone
comparisons still hold, but the "same conditions" line the earlier
reports carry now has this exception attached to it.

Raw data: `results/2026-08-26-m4-avgt.{txt,json}`,
`results/2026-08-26-m4-gc-avgt.{txt,json}`,
`results/2026-08-26-m4-perfasm-segmentvector.txt` and
`results/2026-08-26-m4-perfnorm-16m.txt` (the two profiled runs were
taken under bench-system setup; their ns/op are not citable).

## The kernel

`SegmentVectorDot` is `ArrayVectorDot` over a confined-arena segment:
`SPECIES_PREFERRED` (512 bit, 8 lanes here) as a `static final`, one
vector accumulator, `reduceLanes(ADD)` once after the loop, scalar tail
via `getAtIndex`, no `fma`. Lifecycle as in `SegmentScalarDot`: an
idempotent `close()`, with the fail-fast left on the segments.

The one thing that is genuinely new is the load:

```java
DoubleVector.fromMemorySegment(SPECIES, segmentA,
                               (long) i * Double.BYTES,
                               ByteOrder.nativeOrder());
```

`fromMemorySegment` takes its offset **in bytes**, while the loop counter
runs in elements and advances by `SPECIES.length()`. Passing the element
index compiles, runs, raises no bounds check (byte 1016 is a perfectly
legal request inside an 8192 byte segment) and returns a plausible
number: at 1024 elements, seed 42, it reads 128 overlapping windows of
the first 135 values and yields 270.45 where the answer is 263.79, an
error of 2.5 percent. Nothing in the type system, the API or the runtime
objects. What catches it is the contract test comparing variants against
each other, and only because the tolerance is tight.

Two levels of correctness, as since M3: scalar variants bit-identical,
vector variants within `|expected| * 1e-12`. Both segment variants now
have a lifetime test, since liveness reaches the vector path through
`fromMemorySegment` rather than through `getAtIndex`.

**The vector results are bit-identical to `ArrayVectorDot`**, not merely
within tolerance: same bit pattern at 1024, 1003 and 65536
(263.78649295523115, 254.5880787483512, 16381.981285923777, at 6, 3 and
128 ulp from the ordered scalar sums). The memory layout changes the load
path, not the arithmetic. This is an observation about this
implementation and this JDK, not a guarantee: `reduceLanes` leaves the
intra-vector order deliberately unspecified.

## Numbers (avgt, 3 forks x 5 iterations)

| size | array | segmentScalar | arrayVector | segmentVector | list |
|---|---|---|---|---|---|
| 1 024 | 537.9 ± 3.3 ns | 537.8 ± 11.2 ns | 74.9 ± 7.0 ns | 73.7 ± 2.3 ns | 595.9 ± 6.3 ns |
| 65 536 | 36.98 ± 0.56 µs | 36.70 ± 0.77 µs | 7.22 ± 0.37 µs | 7.59 ± 0.23 µs | 40.00 ± 0.52 µs |
| 16 M | 10.29 ± 0.21 ms | 10.76 ± 0.10 ms | 5.97 ± 0.03 ms | 7.38 ± 0.11 ms | 30.59 ± 0.24 ms |

Speedup of each vector variant over its own scalar baseline:

| size | array / arrayVector | segmentScalar / segmentVector |
|---|---|---|
| 1 024 | 7.2x | 7.3x |
| 65 536 | 5.1x | 4.8x |
| 16 M | 1.7x | 1.5x |

Reproducibility against M3: array, list and arrayVector are within about
2 percent at every size; segmentScalar drifts most (+3.8 percent at
65 536, +5.9 percent at 16 M), consistent with the JDK change plus an
unpinned machine.

Zero steady-state allocation for `segmentVector` as for every other
variant: `gc.count ≈ 0`, `gc.alloc.rate` at the 0.007 MB/s harness
background, `alloc.rate.norm` 0.001 B/op. Vectors stay locals, segments
are native, nothing is materialized.

## Findings

1. **In cache, the segment is the array.** At 1024 the two vector
   variants are 73.7 and 74.9 ns, indistinguishable; at 65 536, 7.59 and
   7.22 µs, error bars nearly touching. Same speedup shape over their
   respective scalar baselines: about 7x in L1, about 5x at 1 MB.
2. **At 16 M the segment loses 23 percent** (7.38 against 5.97 ms), well
   outside the error bars. Not the code, as the disassembly shows below.
   This is the finding the rest of the report is about.
3. **The 2 to 4 percent segmentScalar advantage at 65 536, unexplained
   since M2, is dissolving**: 36.70 against 36.98 µs is 0.8 percent, well
   inside error bars that are ±0.77 and ±0.56. Two milestones of not
   quoting it were the right call.
4. `list` at 16 M holds at 2.97x, stable across M1/M2/M3/M4.

## Perfasm: the same loop body, plus an address

Disassembly at size 1024 (`2026-08-26-m4-perfasm-segmentvector.txt`),
compared against the arrayVector file from M3.

1. **Intrinsified, no fallback**: 95.87 percent of cycles in the C2 stub,
   no executed `VectorSupport` frames, no allocation.
2. **Same loop shape**: `leal 0x40(%r9)`, 64 elements per iteration, C2
   unrolled the 8-lane loop by 8. Eight `vmovups zmm` loads from A, each
   followed by `vmulpd` with the B load fused as a memory operand, then
   eight `vaddpd`.
3. **Same ordered chain**: the eight `vaddpd` are strictly dependent and
   in increasing chunk order (0x0, 0x40, 0x80, 0xc0, 0x100, 0x140, 0x180,
   0x1c0), even though C2 scheduled the loads and multiplies out of
   order. The compiler reorders what it may and leaves the additions
   exactly where the source put them. As in M1.5, M2 and M3: the only
   reassociation anywhere in this program is the one the lanes declare.
4. **No checks in the body**: no bounds check, no arena liveness check.
   Both are hoisted out of the loop.
5. **The one difference** is a seven instruction address preamble per
   iteration (`movl`, `movslq`, `shlq $3`, `movq`, `addq`, `movq`,
   `addq`): base plus `i * 8` computed once, then constant displacements.
   The `* Double.BYTES` of the source is that `shlq $3`. It accounts for
   0.37 percent of samples.

So the closing claim of section 4 holds, and it is now disassembled
rather than asserted: **explicit layout and explicit compute compile to
the same code**. What differs is one address computation.

## Perfnorm at 16 M: what the gap is, and what it is not

Counters per operation (`results/2026-08-26-m4-perfnorm-16m.txt`):

| | arrayVector | segmentVector | delta |
|---|---|---|---|
| ns/op | 5 878 358 | 7 301 944 | +24% |
| cycles | 32.98 M | 41.17 M | +25% |
| instructions | 7.278 M | 9.133 M | +25.5% |
| CPI | 4.532 | 4.508 | equal |
| L1-dcache-loads | 8.534 M | 8.548 M | equal |
| L1-dcache-load-misses | 4.293 M | 5.564 M | **+29.6%** |
| dTLB-load-misses | 53 513 | 65 687 | +23% |

**The extra instructions are exactly the address preamble.** The
difference is 1 854 302 instructions over 262 144 iterations, or 7.07 per
iteration: the seven instructions counted in the disassembly. A useful
corollary comes free: for that arithmetic to work out, the loop at 16 M
must have the same shape as the loop at 1024, same unroll, same 64
elements per iteration. The perfasm taken at 1024 is therefore
representative at 16 M too, and did not need to be repeated there.

**But they are not the bottleneck.** CPI is identical and IPC is 0.22 on
both: the core is waiting on memory roughly 78 percent of the time either
way. What moves is the miss count, at an identical load count. With
268 435 456 bytes touched, the streaming minimum is 4 194 304 lines:

- arrayVector: **1.02 L1-miss events per theoretical line**, the streaming
  ideal;
- segmentVector: **1.33**, a third more miss events for the same lines.

Read those as events rather than as lines refetched: 64-byte accesses are
served over a 256-bit datapath here, so one architectural load can raise the
counter more than once, and misaligned accesses are exactly the case where it
does. What the number establishes is the difference between the two variants
and the fact that it disappears with the alignment fix, not a count of
refetched lines.

The 1.27 M excess misses at roughly 6.4 cycles each account for the 8.2 M
cycle difference. The dTLB delta is real but small: 12 000 extra walks
are about 4 percent of the gap, not the cause.

## Two wrong hypotheses, and the experiment that settled it

Worth recording in full, because the reasoning was wrong twice in ways
that sounded convincing.

**Hypothesis 1, transparent huge pages** (the heap gets them, the arena
does not). Killed by inspection before it cost anything: the system is on
`transparent_hugepage=[madvise]` and the JVM has
`UseTransparentHugePages=false`, so neither the heap nor the arena
requests them. Both run on 4 KB pages.

**Hypothesis 2, cache set conflict.** The segment addresses, printed from
`setup`, are 16 bytes past a page boundary and the two segments of a
variant sit `0x8001000` apart, which is 2^27 + 2^12. That distance is a
multiple of 4096, and the L1D here is 32 KB, 8-way, 64 byte lines, so
64 sets indexed by bits 6 to 11: two streams a multiple of 4096 apart
share a set index for every element of the loop. It fit the symptom, an
L1 miss excess with nothing else disturbed.

**Hypothesis 3, base alignment, which was argued against and was
correct.** `ValueLayout.JAVA_DOUBLE` guarantees `byteAlignment() = 8`,
the natural alignment of the element. A 512 bit load wants 64. Two
arguments were raised against this and both are wrong:

- *"misalignment would show up as CPI, not as misses."* A 64 byte load
  from a base 16 bytes off touches two cache lines, and on this machine
  that surfaces in `L1-dcache-load-misses`, which is precisely the
  counter that moved.
- *"the payload of a `double[]` also starts 16 bytes past its header, so
  the two variants share the property."* An inference about heap layout
  that was never measured, and the measurement disagrees.

**The experiment** (branch `experiment/cache-line-alignment`, commit
`a1e3dbe`, `results/2026-08-26-alignment-*`): one change, from
`arena.allocate(ValueLayout.JAVA_DOUBLE, size)` to
`arena.allocate((long) size * Double.BYTES, 64)`, in both segment
variants. Addresses go from `...010` to `...040`. The distance between
the two segments is unchanged, still `0x8001000`, still a multiple of
4096, so hypothesis 2 is held fixed while hypothesis 3 is varied.

| @16 M | arrayVector (control) | segmentVector | gap |
|---|---|---|---|
| alignment 8 (M4) | 5.975 ± 0.034 ms | 7.376 ± 0.108 ms | +23.5% |
| alignment 64 | 6.108 ± 0.061 ms | 6.202 ± 0.048 ms | +1.5% |

And the mechanism, not just the time (perfnorm on the branch):

| @16 M, per op | arrayVector | segmentVector | miss events per line |
|---|---|---|---|
| alignment 8 | 4 293 072 | 5 563 853 | 1.02 vs 1.33 |
| alignment 64 | 4 279 701 | 4 262 141 | 1.02 vs 1.016 |

Loads unchanged (8.579 M against 8.548 M) and instructions unchanged
(9.083 M against 9.133 M): the seven extra address instructions are still
there and still not the problem. The excess misses are gone and the
segment streams at the ideal rate. Alignment was the cause; the set
distance, held constant throughout, was not.

One more detail closes the loop on the wrong argument above. With the
segments aligned, CPI now **diverges**: 3.802 for the segment against
4.686 for the array, IPC 0.263 against 0.213. The segment executes 25
percent more instructions and fits them into cycles that were stalled
anyway, finishing in 34.5 M cycles against 34.0 M. When the two CPIs were
identical, that was not evidence that alignment was innocent; it was two
opposite effects cancelling.

## Direction for the article

- **Section 4 gets its closing claim disassembled, not asserted.**
  Explicit layout and explicit compute produce the same machine code and
  bit-identical results; the difference is one address computation, worth
  0.37 percent of samples. The vector speedup over each scalar baseline
  is the same shape whether the bytes live in a `double[]` or in a native
  segment.
- **And then it gets its counterweight.** Same code and same semantics do
  not mean same performance once the working set leaves cache, because
  the physical placement of the data starts to matter again. This is the
  bridge into section 6, and it is measured rather than gestured at.
- **The alignment result belongs in section 3, and it is the strongest
  concrete illustration of the thesis so far.** `ValueLayout.JAVA_DOUBLE`
  promises the 8 bytes of the element; a 512 bit load wants 64; the
  natural form of the API leaves 16 percent on the table at 16 M and
  nothing at all in cache, which is why it goes unnoticed. With a
  `double[]` you cannot ask for an alignment, you get what the collector
  gives you. With a `MemorySegment` alignment is an explicit parameter of
  the allocation call. Modelling memory explicitly is not a figure of
  speech: it is a dial that exists, and the default does not turn it.
- **Say it with the right sign.** The misreading is one step away, and it
  is "MemorySegment is slower". The point is that the control exists and
  has to be exercised, and that the API hands you units the object graph
  used to hide: offsets in bytes against indices in elements, alignment
  in bytes against species widths in bits. Three unit slips in two days
  of lab work is a data point about the cost of explicitness, and an
  honest one to report.
- Scoping as always: this kernel, JDK 25.0.4, Zen 4, 512 bit species. The
  lane-count and alignment reasoning transfers; the ratios do not.

## Open

- **Decide whether 64 byte alignment moves into `main`.** The argument
  for it: the `double[]` gets a placement the collector chose and that
  behaves well, so measuring it against a segment left at the element
  default compares an informed choice with a distracted one. If it moves,
  the segmentScalar column changes too, and M2 and M4 numbers are
  superseded. They need not be re-run now: the final controlled session
  regenerates every quoted number anyway, and it only has to happen after
  the change lands.
- Ten `System.out.println` calls are still in commit `a1e3dbe`. They sit
  in `setup`, outside the measured window, so the branch timings are
  valid, but they should come out before the branch is kept as a record.
- Load-only control kernel, still open from M3: it would test the
  bandwidth hypothesis at 16 M and would have been the clean way to
  separate single-stream from two-stream effects here.
- Final controlled session: bench-system, `taskset` pinning to one CCD,
  `@State` per variant (the shared state builds all five variants on
  every fork, `BoxedListDot` with 16 M boxed doubles included), all
  variants, avgt plus gc plus perfasm. Record the Blackhole mode in the
  header.
