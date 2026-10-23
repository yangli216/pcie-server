package com.regionalai.floatingball.server.modules.analytics.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiagnosisChangeSummaryTest {
    @ParameterizedTest
    @MethodSource("summaries")
    void shouldCountOnlyACompleteTopLevelNumericZero(String input, boolean expected) {
        assertEquals(expected, DiagnosisChangeSummary.hasUnchangedDiagnosis(input));
    }

    static Stream<Arguments> summaries() {
        char[] text = new char[50000]; Arrays.fill(text, 'x');
        String padding = new String(text);
        return Stream.of(
            Arguments.of((String) null, false), Arguments.of("", false), Arguments.of("   ", false),
            Arguments.of("null", false), Arguments.of("[]", false), Arguments.of("{}", false),
            Arguments.of("{\"diagnosisChanges\":0}", true),
            Arguments.of("{\"diagnosisChanges\":-0.0}", true),
            Arguments.of("{\"diagnosisChanges\":0e3}", true),
            Arguments.of("{\"diagnosisChanges\":\" 0 \"}", true),
            Arguments.of("{\"diagnosisChanges\":1}", false),
            Arguments.of("{\"diagnosisChanges\":-1}", false),
            Arguments.of("{\"diagnosisChanges\":null}", false),
            Arguments.of("{\"diagnosisChanges\":false}", false),
            Arguments.of("{\"diagnosisChanges\":\"bad\"}", false),
            Arguments.of("{\"diagnosisChanges\":\"\"}", false),
            Arguments.of("{\"diagnosisChanges\":[0]}", false),
            Arguments.of("{\"nested\":{\"diagnosisChanges\":0}}", false),
            Arguments.of("{\"diagnosisChanges\":0,\"diagnosisChanges\":1}", false),
            Arguments.of("{\"diagnosisChanges\":0", false),
            Arguments.of("{\"diagnosisChanges\":0} trailing", false),
            Arguments.of("{\"diagnosisChanges\":0} {}", false),
            Arguments.of("{\"padding\":\"" + padding + "\",\"diagnosisChanges\":0}", true),
            Arguments.of("{\"diagnosisChanges\":0,\"padding\":\"" + padding + "\"}", true),
            Arguments.of("{\"diagnosisChanges\":0,\"padding\":\"" + padding, false)
        );
    }
}
