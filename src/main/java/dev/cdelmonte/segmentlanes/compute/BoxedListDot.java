package dev.cdelmonte.segmentlanes.compute;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class BoxedListDot implements DotProduct {
    List<Double> a;
    List<Double> b;
    
    int size;


    @Override
    public void setup(int size, long seed) {
        this.size = size;
        
        Random random = new Random(seed);

        a =new ArrayList<>(size);
        b =new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            a.add(random.nextDouble());
        }

        for (int i = 0; i < size; i++) {
            b.add(random.nextDouble());    
        }
    }

    @Override
    public double compute() {
        double sum = 0.0; 
        
        for (int i = 0; i < size; i++) {
            sum += a.get(i) * b.get(i);
        }

        return sum;
    }    
}
