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

        this.segmentA = arena.allocate((long) size * Double.BYTES, 64);
        this.segmentB = arena.allocate((long) size * Double.BYTES, 64);

        for (int i = 0; i < size; i++) {
            segmentA.setAtIndex(ValueLayout.JAVA_DOUBLE, i, random.nextDouble());
        }
        for (int i = 0; i < size; i++) {
            segmentB.setAtIndex(ValueLayout.JAVA_DOUBLE, i, random.nextDouble());
        }

        long diff = segmentB.address() - segmentA.address();    
        System.out.println("Class: " + getClass().getSimpleName());
        System.out.println("Segment A address: " + Long.toHexString(segmentA.address()));
        System.out.println("Segment B address: " + Long.toHexString(segmentB.address()));
        System.out.println("Distance between Segment A and Segment B: " + Long.toHexString(diff));
        System.out.println("Exponent: " + Long.numberOfTrailingZeros(diff));
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
