package dev.cdelmonte.segmentlanes.compute;

import java.util.Random;

public class PrimitiveArrayDot implements DotProduct {
    private double[] a;
    private double[] b;

    private int size;

    @Override
    public void setup(int size, long seed) {
        this.size = size;    
        
        Random random = new Random(seed);

        this.a = random.doubles(size).toArray();
        this.b = random.doubles(size).toArray();
    }

    @Override
    public double compute() {
        double sum = 0.0; 
        
        for (int i = 0; i < size; i++) {
            sum += a[i] * b[i];
        }

        return sum;
    }    
}
