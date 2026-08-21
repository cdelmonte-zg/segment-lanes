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
