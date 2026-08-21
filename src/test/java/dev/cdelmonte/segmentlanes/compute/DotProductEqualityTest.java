package dev.cdelmonte.segmentlanes.compute;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class DotProductEqualityTest {
    
    @ParameterizedTest
    @CsvSource({"1024, 42", "1024, 7", "65536, 42"})
    void testBoxedAndArrayAgree(int size, long seed) {
        DotProduct array = new PrimitiveArrayDot();
        DotProduct list = new BoxedListDot();

        array.setup(size, seed);
        list.setup(size, seed);

        assertEquals(array.compute(), list.compute());
    }
}
