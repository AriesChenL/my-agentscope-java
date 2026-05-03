package com.lynn.myagentscopejava.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerateOptionsTest {

    @Test
    void defaultsAreAllNull() {
        GenerateOptions o = GenerateOptions.defaults();
        assertNull(o.getTemperature());
        assertNull(o.getMaxTokens());
        assertTrue(o.getExtraParams().isEmpty());
    }

    @Test
    void builderCarriesValues() {
        GenerateOptions o = GenerateOptions.builder()
                .temperature(0.7)
                .topP(0.9)
                .maxTokens(1024)
                .extraParam("logit_bias", java.util.Map.of("50256", -100))
                .build();
        assertEquals(0.7, o.getTemperature());
        assertEquals(0.9, o.getTopP());
        assertEquals(1024, o.getMaxTokens());
        assertEquals(1, o.getExtraParams().size());
    }

    @Test
    void extraParamsAreImmutable() {
        GenerateOptions o = GenerateOptions.builder().extraParam("k", "v").build();
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> o.getExtraParams().put("x", "y"));
    }
}
