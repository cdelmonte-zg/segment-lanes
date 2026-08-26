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

import dev.cdelmonte.segmentlanes.compute.ArrayLoadControl;
import dev.cdelmonte.segmentlanes.compute.ArrayVectorDot;
import dev.cdelmonte.segmentlanes.compute.BoxedListDot;
import dev.cdelmonte.segmentlanes.compute.PrimitiveArrayDot;
import dev.cdelmonte.segmentlanes.compute.SegmentScalarDot;
import dev.cdelmonte.segmentlanes.compute.SegmentVectorDot;

/**
 * One @State per variant: a trial allocates only the variant it measures.
 * A single shared state would build all five on every fork, and their
 * relative placement in memory would then depend on the allocation order.
 */
@BenchmarkMode(Mode.AverageTime)          // report result: e.g. average time for invocation
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)                   // no shared state left: see the @State classes below
@Fork(value = 1, jvmArgsAppend = "--add-modules=jdk.incubator.vector")
@Warmup(iterations = 2, time = 1)         // passed iterations, waiting for C2
@Measurement(iterations = 3, time = 1)    // measured iterations
public class DotProductBench {

    @Benchmark                            // method measured: only the kernel
    public double array(PrimitiveArrayState s) {
        return s.primitiveArrayDot.compute();
    }

    @Benchmark
    public double list(BoxedListState s) {
        return s.boxedListDot.compute();
    }

    @Benchmark
    public double segmentScalar(SegmentScalarState s) {
        return s.segmentScalarDot.compute();
    }

    @Benchmark
    public double arrayVector(ArrayVectorState s) {
        return s.arrayVectorDot.compute();
    }

    @Benchmark
    public double segmentVector(SegmentVectorState s) {
        return s.segmentVectorDot.compute();
    }

    /** Experimental control for the bandwidth claim, not part of the ladder. */
    @Benchmark
    public double arrayLoadControl(ArrayLoadControlState s) {
        return s.arrayLoadControl.compute();
    }

    @State(Scope.Benchmark)
    public static class PrimitiveArrayState {
        @Param({"1024", "65536", "16777216"})
        int size;

        PrimitiveArrayDot primitiveArrayDot;

        @Setup(Level.Trial)               // executed just one time x fork, not measured
        public void setup() {
            primitiveArrayDot = new PrimitiveArrayDot();
            primitiveArrayDot.setup(size, 42L);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            primitiveArrayDot.close();
        }
    }

    @State(Scope.Benchmark)
    public static class BoxedListState {
        @Param({"1024", "65536", "16777216"})
        int size;

        BoxedListDot boxedListDot;

        @Setup(Level.Trial)
        public void setup() {
            boxedListDot = new BoxedListDot();
            boxedListDot.setup(size, 42L);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            boxedListDot.close();
        }
    }

    @State(Scope.Benchmark)
    public static class SegmentScalarState {
        @Param({"1024", "65536", "16777216"})
        int size;

        SegmentScalarDot segmentScalarDot;

        @Setup(Level.Trial)
        public void setup() {
            segmentScalarDot = new SegmentScalarDot();
            segmentScalarDot.setup(size, 42L);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            segmentScalarDot.close();
        }
    }

    @State(Scope.Benchmark)
    public static class ArrayVectorState {
        @Param({"1024", "65536", "16777216"})
        int size;

        ArrayVectorDot arrayVectorDot;

        @Setup(Level.Trial)
        public void setup() {
            arrayVectorDot = new ArrayVectorDot();
            arrayVectorDot.setup(size, 42L);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            arrayVectorDot.close();
        }
    }

    @State(Scope.Benchmark)
    public static class SegmentVectorState {
        @Param({"1024", "65536", "16777216"})
        int size;

        SegmentVectorDot segmentVectorDot;

        @Setup(Level.Trial)
        public void setup() {
            segmentVectorDot = new SegmentVectorDot();
            segmentVectorDot.setup(size, 42L);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            segmentVectorDot.close();
        }
    }

    @State(Scope.Benchmark)
    public static class ArrayLoadControlState {
        @Param({"1024", "65536", "16777216"})
        int size;

        ArrayLoadControl arrayLoadControl;

        @Setup(Level.Trial)
        public void setup() {
            arrayLoadControl = new ArrayLoadControl();
            arrayLoadControl.setup(size, 42L);
        }
    }
}
