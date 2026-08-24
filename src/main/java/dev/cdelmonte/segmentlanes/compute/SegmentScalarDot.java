package dev.cdelmonte.segmentlanes.compute;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Random;

public class SegmentScalarDot implements DotProduct {
    private int size;
    private Arena arena;
    private MemorySegment segmentA;
    private MemorySegment segmentB;

    @Override
    public void setup(int size, long seed) {
        this.size = size;
        this.arena = Arena.ofConfined();

        Random random = new Random(seed);

        this.segmentA = arena.allocate(ValueLayout.JAVA_DOUBLE, size);
        this.segmentB = arena.allocate(ValueLayout.JAVA_DOUBLE, size);

        for (int i = 0; i < size; i++) {
            segmentA.setAtIndex(ValueLayout.JAVA_DOUBLE, i, random.nextDouble());
        }
        for (int i = 0; i < size; i++) {
            segmentB.setAtIndex(ValueLayout.JAVA_DOUBLE, i, random.nextDouble());
        }
    }

    @Override
    public double compute() {
        double sum = 0.0; 

        for (int i = 0; i < size; i++) {
            sum += segmentA.getAtIndex(ValueLayout.JAVA_DOUBLE, i) 
                    * segmentB.getAtIndex(ValueLayout.JAVA_DOUBLE, i);
        }        

        return sum;
    } 

    @Override
    public void close() {
        if (arena != null) {
            arena.close();
            arena = null;
        }
    }
}
