# M3 report: Vector API on double[] (plus the M4 perfasm for it)

First read of the M3 measurements and the M4 disassembly for
`ArrayVectorDot` (2026-08-25). Same conditions as M1/M2 for the timing
runs (stock machine state: governor powersave, no pinning), so
cross-milestone comparisons are valid. Commit `71bffc5`, JDK 25.0.3,
Ryzen 9 7950X3D, Blackhole mode `compiler` (auto-detected, same as M1/M2).

Raw data: `results/2026-08-25-m3-avgt.{txt,json}`,
`results/2026-08-25-m3-gc-avgt.{txt,json}` and
`results/2026-08-25-m3-perfasm-arrayvector.txt` (perfasm run under
bench-system setup; its ns/op are not citable).

## The kernel

`ArrayVectorDot`: `SPECIES_PREFERRED` (512 bit, 8 lanes on this machine),
one vector accumulator (`acc = acc.add(va.mul(vb))`), `reduceLanes(ADD)`
once after the loop, scalar tail for `size % lanes`. No `fma`: the only
semantic change against `PrimitiveArrayDot` is the addition order.

The contract test now has two levels: the scalar variants stay
bit-identical; the vector variant is held to a relative tolerance
(`|expected| * 1e-12`). At size 1024, seed 42: `263.7864929552308`
(scalar) vs `263.78649295523115` (vector), about 6 ulp. Neither is "the
right one"; the scalar loop fixes the order, the vector loop gives it up
by construction. Size 1003 was added to exercise the tail.

## Numbers (avgt, 3 forks x 5 iterations)

| size | array | arrayVector | ratio | segmentScalar | list |
|---|---|---|---|---|---|
| 1 024 | 539.9 ± 6.1 ns | 70.1 ± 0.9 ns | 7.7x | 533.5 ± 9.0 ns | 592.9 ± 2.4 ns |
| 65 536 | 36.11 ± 0.21 µs | 7.48 ± 0.37 µs | 4.8x | 35.35 ± 0.40 µs | 39.54 ± 0.41 µs |
| 16 M | 10.17 ± 0.08 ms | 5.91 ± 0.03 ms | 1.7x | 10.16 ± 0.20 ms | 30.12 ± 0.18 ms |

Reproducibility: array, segmentScalar and list are within ~2% of M1/M2
at every size; the gc-run timings agree with the avgt run within error.

## Findings

1. **The speedup is not one number, it shrinks with the working set.**
   Three regimes:
   - 1 024 (16 KB, L1): 7.7x, essentially the lane count. Scalar:
     0.53 ns per element; vector: 0.55 ns per 8-element iteration. Same
     time per link of the add chain, eight times the elements per link.
   - 65 536 (1 MB, beyond L1): 4.8x. The chain alone would still give
     ~8x; the rest goes to feeding the loop from L2/L3 (see perfasm,
     point 5). `arrayVector` has the widest error bar of the run (±5%):
     without pinning a 1 MB working set moves between the CCD with and
     without 3D cache. Final session with `taskset`, as planned.
   - 16 M (256 MB, RAM): 1.7x. 256 MB in 5.9 ms is ~43 GB/s, plausibly
     the bandwidth a single core can pull (memory-level parallelism, not
     nominal DDR bandwidth). The scalar loop at 16 M is still
     compute-bound (10.2 ms matches ~3 cycles per element); the vector
     loop is not. **The wall moved from the addition order to memory
     bandwidth.** Hypothesis until a load-only control kernel (sum of
     `a` alone) shows the same ~5.9 ms.
2. **Zero steady-state allocation for `arrayVector`** (gc profiler):
   `gc.count ≈ 0`, `gc.alloc.rate` flat at the 0.007 MB/s harness
   background, `alloc.rate.norm` at or below the other variants. No
   `DoubleVector` is materialized: the value-based discipline (vectors as
   locals only, never fields) held.
3. `segmentScalar` at 65 536 is again ~2% below `array` (35.35 vs 36.11
   µs): same unexplained fact as in M2, still not to be quoted.
4. `list` at 16 M: 30.1 ms, 2.96x, stable across M1/M2/M3.

## Perfasm (M4 for arrayVector): real SIMD, one dependent chain

Disassembly at size 1024 (`2026-08-25-m3-perfasm-arrayvector.txt`).

1. **Intrinsified, no fallback.** 96% of cycles in the C2 stub for
   `arrayVector`, 85% in a single region: the loop. No calls into
   `VectorSupport`, no executed `jdk.incubator.vector` frames (the
   `Double512Vector::lanewise` lines in the listing are inlining
   annotations; that code *became* the instructions). No allocation.
2. **Loop body** (`addl $0x40, %ebx`: 64 elements per iteration, C2
   unrolled the 8-lane loop x8): 8x `vmovups zmm` (load from `a`) each
   followed by `vmulpd` with the `b` load fused as a memory operand, then
   8x `vaddpd`. No `vaddsd`, no `vextract`/`vpshufd` staircase inside the
   loop; the lane reduction happens once, after it (`reduceLanes`, in the
   5.8% and 4.2% regions).
3. **The chain is still there, and it is the bottleneck.** The eight
   `vaddpd` are strictly dependent (`zmm8 -> zmm5 -> zmm5 -> zmm4 -> ...
   -> zmm8`) and in increasing chunk order (0x10, 0x50, 0x90, ...): C2
   unrolled but did not reassociate here either. `acc = acc.add(...)`
   fixes the order of the vector additions and C2 honors it exactly as it
   honored the scalar order. The only reassociation in the whole program
   is the one declared by the lanes. Arithmetic: 8 adds x 3 cycles
   latency = 24 cycles per 64 elements; 1024 elements = 16 iterations =
   384 cycles, which at the observed clock is the measured 68 ns. Sample
   distribution agrees: ~56% on the eight `vaddpd` plus 10% on the `addl`
   right after (skid of the last add), i.e. **~65% on the chain**, the
   same proportion as the `vaddsd` chain in the M2 segment profile. Same
   wall, 8 elements per link instead of 1.
4. **Side by side with M1.5 (`array`, auto-vectorized):**

   | | array (M1.5) | arrayVector (M4) |
   |---|---|---|
   | multiplies | 6x `vmulpd` zmm | 8x `vmulpd` zmm |
   | between mul and add | 32x `vpshufd`, 16x `vextractf128`, 8x `vextracti64x4` | none |
   | adds | 64x `vaddsd`, one ordered chain | 8x `vaddpd`, one ordered chain |
   | chain links per 64 elements | 64 | 8 |
   | measured @1024 | 540 ns | 70 ns (7.7x) |

5. **What it says about 65 536 and 16 M.** The loop consumes 16 loads of
   64 B every 24 cycles, ~43 B/cycle: nothing from L1 (2 loads/cycle),
   but above what L2->L1 sustains on Zen 4 (~32 B/cycle). So at 1 MB the
   loop is already feed-bound rather than chain-bound, and at 256 MB it is
   memory-bound. Consistent with 4.8x and 1.7x; still a hypothesis
   without the load-only control kernel.
6. **Multiple accumulators (a possible M3.5), prediction only.** With 4
   accumulators the chain drops to 2 adds per iteration (6 cycles) but the
   16 loads at 2/cycle cost 8 cycles: ceiling ~3x more at 1024 only,
   nothing at 65 536 or 16 M. Not added to the five-variant ladder: the
   thesis (semantic boundary, declared independence) is already shown
   with one accumulator; "even more declared independence" is a paragraph,
   not a kernel. If wanted as a side experiment, do it in a branch.

## Direction for the article

- §4 gets its measured claim and its mechanism: the compiler never
  reorders floating-point sums, neither the scalar ones (M1.5, M2) nor
  the vector ones (M4); the programmer declares which sums are
  independent (one partial sum per lane), and that declaration is the
  whole speedup. The exact-equality test breaking on the vector variant is
  the same fact seen from the semantic side.
- §4/§6 bridge: the speedup is bounded by the memory system as soon as
  the working set leaves L1. Explicit compute wins only while the data
  arrives; past that, the problem is the physical representation again.
- Scoping, as always: "this kernel, JDK 25.0.3, Zen 4, 512-bit species".
  Ratios do not transfer across ISAs (AVX2: 4 lanes, different L2
  bandwidth); the lane-count reasoning transfers, the numbers do not.

## Open

- `SegmentVectorDot` (M3b): `fromMemorySegment` with byte offsets, scalar
  tail via `getAtIndex`, idempotent `close()`, tolerance + lifetime tests.
  Then one run with all five variants, and its perfasm: if the loop body
  matches this one (base+offset loads instead of array loads), "explicit
  layout + explicit compute" compile to the same code, which is the
  closing line of §4.
- Load-only control kernel for the bandwidth hypothesis at 16 M
  (optional; only if the article wants to say "memory-bound").
- Perfasm at 65 536 for the ~2-4% segmentScalar advantage (optional, as
  in M2; otherwise drop the claim).
- Final controlled session (bench-system + pinning + `@State` per
  variant) regenerates every quoted number.
