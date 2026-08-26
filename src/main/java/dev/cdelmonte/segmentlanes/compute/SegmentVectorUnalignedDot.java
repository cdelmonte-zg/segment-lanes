package dev.cdelmonte.segmentlanes.compute;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import java.util.Random;


/**
 * Experiment only: byte-for-byte identical to {@link SegmentVectorDot} except
 * that the arena is asked for the natural 8-byte alignment of the element
 * (`allocate(ValueLayout.JAVA_DOUBLE, size)`) instead of a 64-byte one.
 *
 * Both variants run in the same JMH session so that alignment is the single
 * variable: same machine state, same fork policy, same @State isolation. The
 * controlled comparison in m4-report.md only covered 16 M and compared two
 * separate runs; this one covers every size in one run.
 */
public class SegmentVectorUnalignedDot implements DotProduct {
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
