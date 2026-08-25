package dev.cdelmonte.segmentlanes.compute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class DotProductContractTest {

    @ParameterizedTest
    @CsvSource({"1024, 42", "1024, 7", "65536, 42", "1003, 42"})
    void testVariantsAgree(int size, long seed) {
        try(
            DotProduct array = new PrimitiveArrayDot();
            DotProduct list = new BoxedListDot();
            DotProduct segmentScalar = new SegmentScalarDot();
            DotProduct arrayVector = new ArrayVectorDot();
        ) {
            array.setup(size, seed);
            list.setup(size, seed);
            segmentScalar.setup(size, seed);
            arrayVector.setup(size, seed);

            double expected = array.compute();
            assertEquals(expected, list.compute());
            assertEquals(expected, segmentScalar.compute());

            // Reduction order differs by construction; see README
            assertEquals(expected, arrayVector.compute(), Math.abs(expected) * 1e-12);
        }
    }

    @Test
    void testSegmentScalarLifetime() {
        DotProduct segmentScalar = new SegmentScalarDot();
        segmentScalar.setup(1024, 42);
        segmentScalar.compute();
        segmentScalar.close();
        assertThrows(IllegalStateException.class, () -> segmentScalar.compute());

        // Calling close more than once has no effect
        segmentScalar.close();
    }
}
