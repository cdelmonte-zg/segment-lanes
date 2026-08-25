package dev.cdelmonte.segmentlanes.compute;

import java.util.Random;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;


public class ArrayVectorDot implements DotProduct {
    private int size;
    private double[] a;
    private double[] b;

    private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;


    @Override
    public void setup(int size, long seed) {
        this.size = size;
        Random random = new Random(seed);
        this.a = random.doubles(size).toArray();
        this.b = random.doubles(size).toArray();
    }

    @Override
    public double compute() {
        int upperBound = SPECIES.loopBound(size);
        DoubleVector va;
        DoubleVector vb;
        DoubleVector acc = DoubleVector.zero(SPECIES);
        for (int i = 0; i < upperBound; i += SPECIES.length()) {
            va = DoubleVector.fromArray(SPECIES, a, i);
            vb = DoubleVector.fromArray(SPECIES, b, i);
            acc = acc.add(va.mul(vb));
        }
        double sum = acc.reduceLanes(VectorOperators.ADD);
        for (int i = upperBound; i < size; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
