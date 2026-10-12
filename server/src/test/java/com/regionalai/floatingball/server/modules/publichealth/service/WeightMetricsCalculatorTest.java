package com.regionalai.floatingball.server.modules.publichealth.service;

import org.junit.jupiter.api.Test;
import com.regionalai.floatingball.server.modules.publichealth.dto.PublicHealthRequest;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class WeightMetricsCalculatorTest {

    @Test void reproducesVersionedLegacyUnitsAndOneDecimal() throws Exception {
        PublicHealthRequest r = PublicHealthTestFixture.parsed("weight_management").request;
        WeightMetricsCalculator c = new WeightMetricsCalculator(PublicHealthTestFixture.M);
        ObjectNode male = c.calculate(r);
        assertEquals("24.2", male.path("bmi").asText());
        assertEquals("1603.0", male.path("bmrKcalPerDay").asText());
        r.getPatient().setSex("female");
        assertEquals("1438.0", c.calculate(r).path("bmrKcalPerDay").asText());
        assertEquals(WeightMetricsCalculator.VERSION, male.path("formulaVersion").asText());
    }

    @Test void unknownSexCannotFallThroughToFemale() throws Exception {
        PublicHealthRequest r = PublicHealthTestFixture.parsed("weight_management").request;
        r.getPatient().setSex("unknown");
        assertThrows(PublicHealthException.class, () -> new WeightMetricsCalculator(PublicHealthTestFixture.M).calculate(r));
    }

    @Test void enforcesOutputMetricBoundsFromTheContract() throws Exception {
        PublicHealthRequest request = PublicHealthTestFixture.parsed("weight_management").request;
        request.getFacts().getWeightProfile().setHeightCm(new BigDecimal("20"));
        request.getFacts().getWeightProfile().setWeightKg(new BigDecimal("1000"));
        PublicHealthException failure = assertThrows(PublicHealthException.class,
                () -> new WeightMetricsCalculator(PublicHealthTestFixture.M).calculate(request));
        assertEquals("INVALID_REQUEST", failure.getCode());
    }

    @Test void matchesPythonDoubleArithmeticAndRoundAtHalfBoundaries() throws Exception {
        // Expected values were independently calculated with Python's float and
        // round(value, 1), including a cm -> m -> cm conversion before BMR.
        String[][] cases = {
                {"200", "49", "40", "male", "12.2", "1465.3"},
                {"200", "49.4", "40", "male", "12.3", "1470.8"},
                {"170", "70.5", "40", "male", "24.4", "1609.8"},
                {"165.1", "60.7", "30", "female", "22.3", "1387.8"},
                {"160", "58.624", "35", "female", "22.9", "1335.4"},
                {"173.1", "69.15", "44", "male", "23.1", "1579.7"},
                {"177.7", "85.55", "65", "male", "27.1", "1684.5"},
                {"166.6", "59.95", "72", "female", "21.6", "1186.0"}
        };
        WeightMetricsCalculator calculator = new WeightMetricsCalculator(PublicHealthTestFixture.M);
        for (String[] example : cases) {
            PublicHealthRequest request = PublicHealthTestFixture.parsed("weight_management").request;
            request.getFacts().getWeightProfile().setHeightCm(new BigDecimal(example[0]));
            request.getFacts().getWeightProfile().setWeightKg(new BigDecimal(example[1]));
            request.getPatient().setAgeYears(Integer.valueOf(example[2]));
            request.getPatient().setSex(example[3]);
            ObjectNode result = calculator.calculate(request);
            assertEquals(example[4], result.path("bmi").asText());
            assertEquals(example[5], result.path("bmrKcalPerDay").asText());
        }
    }
}
