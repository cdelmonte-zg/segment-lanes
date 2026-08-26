package dev.cdelmonte.segmentlanes;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import dev.cdelmonte.segmentlanes.compute.ArrayVectorDot;
import dev.cdelmonte.segmentlanes.compute.BoxedListDot;
import dev.cdelmonte.segmentlanes.compute.PrimitiveArrayDot;
import dev.cdelmonte.segmentlanes.compute.SegmentScalarDot;
import dev.cdelmonte.segmentlanes.compute.SegmentVectorDot;

@BenchmarkMode(Mode.AverageTime)          // report result: e.g. average time for invocation
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)                   // class containing shared state of benchmark
@Fork(value = 1, jvmArgsAppend = "--add-modules=jdk.incubator.vector")
@Warmup(iterations = 2, time = 1)         // passed iterations, waiting for C2
@Measurement(iterations = 3, time = 1)    // measured iterations
public class DotProductBench {

    @Param({"1024", "65536", "16777216"})
    int size;

    PrimitiveArrayDot arrayDot;
    BoxedListDot boxedDot;
    SegmentScalarDot segmentScalarDot;
    ArrayVectorDot arrayVectorDot;
    SegmentVectorDot segmentVectorDot;

    @Setup(Level.Trial)                   // executed just one time x fork, not measured
    public void setup() {
        arrayDot = new PrimitiveArrayDot();
        arrayDot.setup(size, 42L);

        boxedDot = new BoxedListDot();
        boxedDot.setup(size, 42L);

        segmentScalarDot = new SegmentScalarDot();
        segmentScalarDot.setup(size, 42L);

        arrayVectorDot = new ArrayVectorDot();
        arrayVectorDot.setup(size, 42L);

        segmentVectorDot = new SegmentVectorDot();
        segmentVectorDot.setup(size, 42L);
    }

    @Benchmark                            // method measured: only the kernel
    public double array() {
        return arrayDot.compute();
    }

    @Benchmark
    public double list() {
        return boxedDot.compute();
    }

    @Benchmark
    public double segmentScalar() {
        return segmentScalarDot.compute();
    }

    @Benchmark
    public double arrayVector() {
        return arrayVectorDot.compute();
    }

    @Benchmark
    public double segmentVector() {
        return segmentVectorDot.compute();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        arrayDot.close();
        boxedDot.close();
        segmentScalarDot.close();
        arrayVectorDot.close();
        segmentVectorDot.close();
    }
}
