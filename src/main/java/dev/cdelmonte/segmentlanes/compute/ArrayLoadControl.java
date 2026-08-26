package dev.cdelmonte.segmentlanes.compute;

import java.util.Random;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Experimental control, NOT a sixth variant of the ladder.
 *
 * It exists to test one claim: that at 16 M elements the dot product is
 * limited by memory bandwidth rather than by arithmetic. To do that it
 * must touch exactly the same bytes as {@link ArrayVectorDot} (same fill
 * contract, same two arrays, same traversal) while doing as little work
 * as the compiler will let it get away with.
 *
 * Hence two independent accumulators and no multiply: the dependency
 * chain is one add per accumulator per iteration, shorter than the dot
 * product's single chain, so any remaining time is not the arithmetic.
 * If this kernel takes as long as {@link ArrayVectorDot} at 16 M, the
 * dot product is feed-bound and the bandwidth reading holds; if it is
 * markedly faster, there is still compute on the critical path.
 *
 * The sum is returned so the loads cannot be optimized away.
 */
public class ArrayLoadControl {

    private int size;
    private double[] a;
    private double[] b;

    private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;

    public void setup(int size, long seed) {
        this.size = size;
        Random random = new Random(seed);
        this.a = random.doubles(size).toArray();
        this.b = random.doubles(size).toArray();
    }

    public double compute() {
        int upperBound = SPECIES.loopBound(size);
        DoubleVector accA = DoubleVector.zero(SPECIES);
        DoubleVector accB = DoubleVector.zero(SPECIES);
        for (int i = 0; i < upperBound; i += SPECIES.length()) {
            accA = accA.add(DoubleVector.fromArray(SPECIES, a, i));
            accB = accB.add(DoubleVector.fromArray(SPECIES, b, i));
        }
        double sum = accA.reduceLanes(VectorOperators.ADD)
                   + accB.reduceLanes(VectorOperators.ADD);
        for (int i = upperBound; i < size; i++) {
            sum += a[i] + b[i];
        }
        return sum;
    }
}
