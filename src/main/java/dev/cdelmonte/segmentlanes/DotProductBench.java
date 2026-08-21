package dev.cdelmonte.segmentlanes;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;

import dev.cdelmonte.segmentlanes.compute.PrimitiveArrayDot;

@BenchmarkMode(Mode.AverageTime)          // report result: e.g. average time for invocation
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)                   // class containing shared state of benchmark
@Fork(value = 1, jvmArgsAppend = "--add-modules=jdk.incubator.vector")
@Warmup(iterations = 2, time = 1)         // passed iterations, waiting for C2
@Measurement(iterations = 3, time = 1)    // measured iterations
public class DotProductBench {

    @Param({"1024"})
    int size;

    PrimitiveArrayDot arrayDot;

    @Setup(Level.Trial)                   // executed just ioone time x fork, not measured
    public void setup() {
        arrayDot = new PrimitiveArrayDot();
        arrayDot.setup(size, 42L);
    }

    @Benchmark                            // method measured: only the kernel
    public double array() {
        return arrayDot.compute();
    }
}