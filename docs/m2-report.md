# M2 report: MemorySegment scalar variant

First read of the M2 measurements (2026-08-24). Working numbers, not the
final controlled session: stock machine state (governor powersave, no
pinning), same conditions as the M1 runs, so cross-milestone comparisons
are valid. Commit `ed9f969`, JDK 25.0.3, Ryzen 9 7950X3D.

Raw data: `results/2026-08-24-m2-avgt.{txt,json}`,
`results/2026-08-24-m2-gc-avgt.{txt,json}` and
`results/2026-08-24-m2-perfasm-segment.txt` (perfasm run under
bench-system setup; its ns/op are not citable).

## Numbers (avgt, 3 forks x 5 iterations)

| size | array | segmentScalar | list |
|---|---|---|---|
| 1 024 | 539.2 ± 3.5 ns | 538.2 ± 9.0 ns | 596.6 ± 3.2 ns |
| 65 536 | 36.88 ± 0.49 µs | 35.47 ± 0.47 µs | 39.98 ± 0.73 µs |
| 16 M | 10.35 ± 0.11 ms | 10.09 ± 0.14 ms | 30.30 ± 0.20 ms |

Reproducibility against M1 (array: 537 ns / 36.1 µs / 10.12 ms) is within
~2% at every size; the gc-run timings agree with the avgt run within error.

## Findings

1. **`getAtIndex` on a native segment pays no toll against `double[]`.**
   Parity at 1 024, and the segment is never slower at any size. The
   pre-run hypothesis — segment bounds/liveness checks inhibit C2's
   vectorization of the multiplies — is contradicted by the timings: at
   65 536 (in-cache, compute-bound) a scalar-only multiply path would show
   up as a large regression, not parity.

2. **The segment is slightly *faster* than the array at 65 536** (35.47 vs
   36.88 µs, non-overlapping error intervals, ~4%), and ~2.5% faster at
   16 M (borderline with the error bars). This is an unexplained fact, not
   a conclusion. Candidate causes to check in the assembly: allocation
   alignment (native allocation vs. array-header offset), a slightly
   different loop shape, prefetch behavior. **Do not quote "segment is
   faster" in the article until perfasm explains the mechanism.**

3. **Zero steady-state allocation for all three variants** (gc profiler):
   `gc.count ≈ 0` everywhere, `gc.alloc.rate` flat at 0.007 MB/s (harness
   background; `alloc.rate.norm` grows only because op duration grows).
   The segment read path allocates nothing.

4. **The boxed baseline behaves as in M1**: ~1.10x at in-cache sizes,
   ~2.97x at 16 M (working set ~3.5x in bytes → RAM bandwidth), unchanged.

## Perfasm: the segment loop is not auto-vectorized at all — and it does not matter

Disassembly at size 1024 (`2026-08-24-m2-perfasm-segment.txt`), compared
against the M1.5 array file (`2026-08-21-m1-perfasm-array.txt`). Hot-region
instruction mix:

| | array (M1.5) | segmentScalar (M2) |
|---|---|---|
| multiplies | 6× `vmulpd` zmm (packed, 8 doubles each) | 8× `vmulsd` (scalar) |
| lane staircase | 32× `vpshufd`, 16× `vextractf128`, 8× `vextracti64x4` | none |
| adds | 64× `vaddsd`, one ordered chain | 8× `vaddsd`, one ordered chain |
| loop shape | unrolled, SuperWord-vectorized multiplies | unrolled x8, fully scalar |

Findings:

1. **C2 does not auto-vectorize the `getAtIndex` loop on this kernel**:
   the multiplies stay scalar (`vmovsd` + `vmulsd`), no packed instruction
   anywhere in the hot region. The array loop gets `vmulpd` on the same
   JDK and machine. So there *is* a SuperWord difference between `double[]`
   and `MemorySegment` access — scope this claim to this kernel/JDK 25.0.3/
   Zen 4; do not generalize to "C2 cannot vectorize segment loops".
2. **The timing parity is the proof of the M1.5 claim.** A fully scalar
   segment loop (538 ns) matches the packed-multiply array loop (539 ns)
   exactly, because both execute the same serial bottleneck: one ordered
   `vaddsd` chain, one add per element (~65% of cycles cluster on the add
   chain in the segment profile). The array's vectorized multiplies buy
   nothing; the segment's missing vectorization costs nothing. The critical
   path is the reduction order in both — the strongest evidence yet that
   the boundary is semantic (FP addition order), not the memory
   abstraction.
3. **No per-element safety checks in the loop body.** The unrolled body is
   pure address arithmetic (`base + index*8` via hoisted `ScopedMemoryAccess`)
   plus loads/muls/adds: bounds and arena-liveness checks are hoisted out
   of the counted loop. "Explicit region and layout" compiles down to the
   same addressing as an array access.
4. The ~4% segment advantage at 65 536 remains unexplained (perfasm ran at
   1024, where times are equal). Plausible but unproven: the array pays
   the packed-mul + lane-staircase micro-ops for no critical-path benefit,
   while the segment's flat scalar schedule is marginally cheaper. Do not
   attribute without a perfasm run at 65 536.

## Direction for the article

- §3 gets its measured claim: on this hot path, modeling the region
  explicitly (`MemorySegment` + `ValueLayout` + confined `Arena`) costs
  nothing at access time. The explicit representation is not a tax; the
  boxed representation is.
- §4 gets its setup, stronger than planned: on this kernel the
  auto-vectorizer's reach differs between array (packed muls) and segment
  (nothing) — and the difference is invisible in the timing, because the
  ordered reduction is the wall in both. That is exactly the gap the
  Vector API exists to close: only by *declaring* a different reduction
  order (M3) can SIMD start paying. The exact-equality test passing on
  both scalar variants is the same fact seen from the semantic side.

## Open

- Perfasm at 65 536 for the ~4% segment advantage (optional; only if the
  article ends up wanting to say anything about it — otherwise drop the
  claim entirely).
- Update the README correctness note at M3: "all variants produce
  bit-identical results" holds for the scalar variants only; the Vector
  API variants will legitimately break exact equality (reduction order).
- Machine state: bench-system `restore` still to be run (needs sudo).
