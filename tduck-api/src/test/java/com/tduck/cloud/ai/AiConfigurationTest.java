package com.tduck.cloud.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tduck.cloud.ai.config.AiConfiguration;
import com.tduck.cloud.ai.config.AiProperties;
import com.tduck.cloud.ai.vo.AiStatusVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AiConfigurationTest {

    @AfterEach
    void tearDown() {
        AiConfiguration.apply(null);
    }

    @Test
    void emptyConfigClearsServices() {
        AiConfiguration.apply(null);
        assertFalse(AiConfiguration.isConfigured());
        assertNull(AiConfiguration.getAiService(true, false));
        AiStatusVO status = AiConfiguration.getStatus();
        assertFalse(status.isConfigured());
        assertTrue(status.getMessage().contains("未配置"));
    }

    @Test
    void disabledOrMissingKeyIsNotConfigured() {
        AiProperties disabled = props();
        disabled.setEnabled(false);
        AiConfiguration.apply(disabled);
        assertFalse(AiConfiguration.isConfigured());
        assertTrue(AiConfiguration.getStatus().getMessage().contains("未启用"));

        AiProperties noKey = props();
        noKey.setEnabled(true);
        noKey.setApiKey(" ");
        AiConfiguration.apply(noKey);
        assertFalse(AiConfiguration.isConfigured());
        assertTrue(AiConfiguration.getStatus().getMessage().contains("API Key"));
    }

    @Test
    void invalidBaseUrlIsRejected() {
        AiProperties invalid = props();
        invalid.setEnabled(true);
        invalid.setBaseUrl("ftp://api.deepseek.com/v1");
        AiConfiguration.apply(invalid);
        assertFalse(AiConfiguration.isConfigured());
        assertTrue(AiConfiguration.getStatus().getMessage().contains("Base URL"));
    }

    @Test
    void officialConfigBuildsFourServices() {
        AiProperties props = props();
        props.setEnabled(true);
        props.setBaseUrl("https://api.deepseek.com/v1");
        AiConfiguration.apply(props);

        assertTrue(AiConfiguration.isConfigured());
        assertNotNull(AiConfiguration.getAiService(true, false));
        assertNotNull(AiConfiguration.getAiService(false, false));
        assertNotNull(AiConfiguration.getAiService(true, true));
        assertNotNull(AiConfiguration.getAiService(false, true));

        AiStatusVO status = AiConfiguration.getStatus();
        assertTrue(status.isConfigured());
        assertTrue(status.isOfficial());
        assertEquals("native", status.getThinkingMode());
        assertEquals("deepseek-v4-flash", status.getModelName());
        assertEquals("deepseek-v4-pro", status.getReasoningModelName());
        assertEquals("high", status.getReasoningEffort());
        assertTrue(status.isSupportsThinking());
    }

    @Test
    void nonOfficialConfigUsesModelMode() {
        AiProperties props = props();
        props.setEnabled(true);
        props.setBaseUrl("https://gateway.example.com/v1");
        AiConfiguration.apply(props);

        assertTrue(AiConfiguration.isConfigured());
        AiStatusVO status = AiConfiguration.getStatus();
        assertFalse(status.isOfficial());
        assertEquals("model", status.getThinkingMode());
        assertTrue(status.isSupportsThinking());
    }

    @Test
    void savingEmptyConfigClearsOldServices() {
        AiProperties props = props();
        props.setEnabled(true);
        AiConfiguration.apply(props);
        assertTrue(AiConfiguration.isConfigured());

        AiConfiguration.apply(null);
        assertFalse(AiConfiguration.isConfigured());
        assertNull(AiConfiguration.getAiService(true, true));
    }

    private AiProperties props() {
        AiProperties props = new AiProperties();
        props.setApiKey("sk-test-key-for-unit-test");
        props.setModelName("deepseek-v4-flash");
        props.setReasoningModelName("deepseek-v4-pro");
        props.setReasoningEffort("high");
        props.setTimeout(120);
        return props;
    }
}
