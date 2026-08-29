package io.github.libtmux.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TrimTest {

    @Test
    void appendingOutputRetainsOnlyTheBoundedTailAndCountsEverythingDropped() {
        Trim.Trimmed retained = new Trim.Trimmed(List.of(), 0);

        for (int batch = 0; batch < 100; batch++) {
            List<String> fresh = new ArrayList<>();
            for (int line = 0; line < 100; line++) {
                fresh.add("line-" + batch + "-" + line);
            }
            retained = Trim.append(retained, fresh, 10);
        }

        assertEquals(10, retained.lines().size());
        assertEquals("line-99-99", retained.lines().get(9));
        assertEquals(9_990, retained.dropped());
    }
}
