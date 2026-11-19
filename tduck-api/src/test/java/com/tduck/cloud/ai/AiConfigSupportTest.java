package com.tduck.cloud.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tduck.cloud.ai.config.AiConfigSupport;
import com.tduck.cloud.ai.config.AiProperties;
import org.junit.jupiter.api.Test;

class AiConfigSupportTest {

    @Test
    void normalizeBaseUrl() {
        assertEquals("https://api.deepseek.com/v1",
                AiConfigSupport.normalizeBaseUrl(" https://api.deepseek.com/v1/ "));
        assertEquals("https://api.deepseek.com/v1",
                AiConfigSupport.normalizeBaseUrl("https://api.deepseek.com/v1/chat/completions"));
        assertEquals("https://gateway.example.com/v1",
                AiConfigSupport.normalizeBaseUrl("https://gateway.example.com/v1/"));
        assertNull(AiConfigSupport.normalizeBaseUrl("ftp://api.deepseek.com"));
        assertNull(AiConfigSupport.normalizeBaseUrl("https://user:pass@api.deepseek.com"));
        assertNull(AiConfigSupport.normalizeBaseUrl("https://api.deepseek.com?x=1"));
        assertNull(AiConfigSupport.normalizeBaseUrl(""));
    }

    @Test
    void officialDomainIsStrict() {
        assertTrue(AiConfigSupport.isOfficialDeepSeek("https://api.deepseek.com/v1"));
        assertFalse(AiConfigSupport.isOfficialDeepSeek("https://api.deepseek.com.evil.com/v1"));
        assertFalse(AiConfigSupport.isOfficialDeepSeek("https://gateway.example.com/api.deepseek.com"));
        assertFalse(AiConfigSupport.isOfficialDeepSeek(""));
    }

    @Test
    void legacyModelNamesMigrate() {
        AiProperties props = new AiProperties();
        props.setModelName("deepseek-chat");
        props.setReasoningModelName("deepseek-reasoner");
        assertEquals(AiConfigSupport.DEFAULT_MODEL, AiConfigSupport.resolveModelName(props, false));
        assertEquals(AiConfigSupport.DEFAULT_REASONING_MODEL, AiConfigSupport.resolveModelName(props, true));

        props.setModelName(" ");
        props.setReasoningModelName(null);
        assertEquals(AiConfigSupport.DEFAULT_MODEL, AiConfigSupport.resolveModelName(props, false));
        assertEquals(AiConfigSupport.DEFAULT_REASONING_MODEL, AiConfigSupport.resolveModelName(props, true));
        assertEquals(AiConfigSupport.DEFAULT_MODEL, AiConfigSupport.resolveModelName(null, false));
    }

    @Test
    void effortAndTimeout() {
        assertEquals("low", AiConfigSupport.normalizeEffort("LOW"));
        assertEquals("max", AiConfigSupport.normalizeEffort("max"));
        assertEquals("high", AiConfigSupport.normalizeEffort("unknown"));
        assertEquals("high", AiConfigSupport.normalizeEffort(null));

        assertEquals(300000, AiConfigSupport.timeoutMillis(300));
        assertEquals(30000, AiConfigSupport.timeoutMillis(1));
        assertEquals(600000, AiConfigSupport.timeoutMillis(9999));
    }
}
