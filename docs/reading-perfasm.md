# Reading perfasm output

Field guide for the `-prof perfasm` files in `results/`. The examples come from
`2026-08-21-m1-perfasm-array.txt` (the M1.5 baseline check).

## 1. AT&T syntax, three rules

```
vmulpd  0x150(%rdi,%rsi,8), %zmm1, %zmm21
```

- **The destination is the last operand** (Intel syntax, used by most manuals,
  puts it first — mind the flip when cross-reading).
- `%` prefixes registers, `$` prefixes immediates.
- Memory operands read `displacement(base, index, scale)` =
  `base + index*scale + displacement`. So `0x150(%rdi,%rsi,8)` is
  `rdi + rsi*8 + 0x150`: array base in `rdi`, loop index in `rsi`, `*8`
  because a double is 8 bytes. The whole line: "multiply 8 doubles loaded
  from memory by `zmm1`, result into `zmm21`".

## 2. Registers

- `rax, rbx, rdi, rsi, ...`: 64-bit integer registers (pointers, indices).
- `xmm` / `ymm` / `zmm`: the **same** vector register viewed at 128 / 256 /
  512 bits — `xmm5` is the low quarter of `zmm5`. That is why lane
  extraction "walks down the stairs" from zmm to ymm to xmm.

## 3. Decoding instruction names

`v` prefix = AVX encoding. The suffix carries the key information:

| suffix | meaning | example |
|---|---|---|
| `sd` | **s**calar **d**ouble: 1 double | `vaddsd` |
| `pd` | **p**acked **d**ouble: every double in the register | `vmulpd` = 8 on zmm |
| `ss`/`ps` | same for float | — |

Instructions starting with `vp` operate on packed integers (`vpshufd` =
shuffle of 32-bit words); C2 uses them here only to move lanes around, not
for arithmetic.

## 4. The vocabulary of the M1.5 file

- `vmovups` — unaligned vector load/store (the "packed single" in the name is
  historical; C2 uses it for any payload);
- `vaddsd %xmm5, %xmm0, %xmm0` — `xmm0 += low lane of xmm5`: one link of the
  ordered add chain;
- `vpshufd $0xe, %xmm5, %xmm15` — brings the high 64-bit lane of a 128-bit
  register down, so the next `vaddsd` can consume it;
- `vextractf128 $1, %ymm5, %xmm15` — extracts the high half of 256 bits;
- `vextracti64x4 $1, %zmm5, %ymm14` — extracts the high half of 512 bits.

These four, repeated, are the lane staircase: from one zmm holding 8
products, C2 pulls lanes out one by one and adds them in source order.
Packed multiplies, ordered scalar sums — the FP-reassociation boundary made
visible.

## 5. The percentages

Fraction of perf samples landing on that instruction. Two caveats: sampling
**skid** often attributes a sample to the instruction *after* the one that
spent the time, so read clusters, not single lines; and percentages are of
the whole run, including regions not shown.

## 6. Intel syntax, if preferred

perfasm can emit it directly (destination first, no `%`, memory as
`[rdi+rsi*8+0x150]`):

```bash
-prof perfasm:intelSyntax=true
```

Pick one syntax and stay consistent across archived files.

## 7. References

- **felixcloutier.com/x86** — per-instruction reference (what exactly
  `vpshufd` does with `$0xe`); Intel syntax.
- **Agner Fog, *Instruction tables*** (agner.org/optimize) and **uops.info** —
  latency/throughput per microarchitecture (source of the ~3-cycle `vaddsd`
  latency on Zen 4).
- The assembly chapter of Bakhvalov, *Performance Analysis and Tuning on
  Modern CPUs*, bridges the two levels above.
