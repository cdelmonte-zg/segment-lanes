package dev.cdelmonte.segmentlanes.compute;

public interface DotProduct extends AutoCloseable {

    /** 
     * {@code new Random(seed)}, all of {@code a} first, then all of {@code b},
     * values in [0, 1). Any deviation breaks cross-variant comparability.
     */
    void setup(int size, long seed);
    
    
    /**
     * Computes the dot product over the vectors built by setup.
     * Kernel only: no allocation, no I/O; everything here is measured.
     */
    double compute();


    /**
     * releases any off-heap resources; no-op for heap-based variants; 
     * must be called on the owning thread for confined arenas. 
     * Calling close more than once has no effect  
     */
    default void close() {}
}
