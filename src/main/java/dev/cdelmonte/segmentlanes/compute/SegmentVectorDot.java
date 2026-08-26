package dev.cdelmonte.segmentlanes.compute;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import java.util.Random;


public class SegmentVectorDot implements DotProduct {
    private int size;
    private Arena arena;
    private MemorySegment segmentA;
    private MemorySegment segmentB;

    private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;

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
        int upperBound = SPECIES.loopBound(size);
        DoubleVector va;
        DoubleVector vb;
        DoubleVector acc = DoubleVector.zero(SPECIES);
        for (int i = 0; i < upperBound; i += SPECIES.length()) {
            va = DoubleVector.fromMemorySegment(
                SPECIES, segmentA, (long) i * Double.BYTES, ByteOrder.nativeOrder());
            vb = DoubleVector.fromMemorySegment(
                SPECIES, segmentB, (long) i * Double.BYTES, ByteOrder.nativeOrder());
            acc = acc.add(va.mul(vb));
        }
        double sum = acc.reduceLanes(VectorOperators.ADD);
        
        for (int i = upperBound; i < size; i++) {
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
