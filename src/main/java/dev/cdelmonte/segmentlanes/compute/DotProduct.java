package dev.cdelmonte.segmentlanes.compute;

public interface DotProduct {

    /* {@code new Random(seed)}, all of {@code a} first, then all of {@code b},
     * values in [0, 1). Any deviation breaks cross-variant comparability.
     */
    void setup(int size, long seed);
    
    
    /**
     * Computes the dot product over the vectors built by setup.
     * Kernel only: no allocation, no I/O; everything here is measured.
     */
    double compute();
}
