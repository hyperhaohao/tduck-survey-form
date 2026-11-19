package com.tduck.cloud.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tduck.cloud.ai.util.AiStreamParser;
import org.junit.jupiter.api.Test;

class AiStreamParserTest {

    @Test
    void parsesContentChunk() {
        AiStreamParser.ParsedLine line = AiStreamParser.parse(
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"[{\\\"type\\\"\"}}]}");
        assertEquals("[{\"type\"", line.getContent());
        assertNull(line.getReasoning());
        assertFalse(line.isDone());
    }

    @Test
    void parsesReasoningContent() {
        AiStreamParser.ParsedLine line = AiStreamParser.parse(
                "data:{\"choices\":[{\"delta\":{\"reasoning_content\":\"先分析需求\"}}]}");
        assertEquals("先分析需求", line.getReasoning());
        assertNull(line.getContent());
    }

    @Test
    void recognizesDoneVariants() {
        assertTrue(AiStreamParser.parse("data: [DONE]").isDone());
        assertTrue(AiStreamParser.parse("data:[DONE]").isDone());
        AiStreamParser.ParsedLine finish = AiStreamParser.parse(
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}");
        assertTrue(finish.isDone());
        assertEquals("stop", finish.getFinishReason());
    }

    @Test
    void ignoresHeartbeatAndMetadataLines() {
        assertFalse(AiStreamParser.parse("").isDone());
        assertFalse(AiStreamParser.parse(": keep-alive").isDone());
        assertFalse(AiStreamParser.parse("event: message").isDone());
        assertFalse(AiStreamParser.parse("id: 1").isDone());
    }

    @Test
    void classifiesErrors() {
        AiStreamParser.ParsedLine auth = AiStreamParser.parse(
                "data: {\"error\":\"Server returned HTTP response code: 401 for URL\"}");
        assertEquals(AiStreamParser.AI_AUTH_FAILED, auth.getErrorCode());
        assertFalse(auth.isRetryable());

        AiStreamParser.ParsedLine model = AiStreamParser.parse(
                "data: {\"error\":\"Model Not Exist\"}");
        assertEquals(AiStreamParser.AI_MODEL_NOT_FOUND, model.getErrorCode());

        AiStreamParser.ParsedLine limited = AiStreamParser.parse(
                "data: {\"error\":\"429 Too Many Requests, rate limit\"}");
        assertEquals(AiStreamParser.AI_RATE_LIMITED, limited.getErrorCode());
        assertTrue(limited.isRetryable());

        AiStreamParser.ParsedLine timeout = AiStreamParser.parse(
                "data: {\"error\":\"Read timed out\"}");
        assertEquals(AiStreamParser.AI_TIMEOUT, timeout.getErrorCode());

        AiStreamParser.ParsedLine network = AiStreamParser.parse(
                "data: {\"error\":\"UnknownHostException: api.deepseek.com\"}");
        assertEquals(AiStreamParser.AI_NETWORK_ERROR, network.getErrorCode());

        AiStreamParser.ParsedLine upstream = AiStreamParser.parse(
                "data: {\"error\":\"something bad\"}");
        assertEquals(AiStreamParser.AI_UPSTREAM_ERROR, upstream.getErrorCode());
    }

    @Test
    void sanitizesKeys() {
        String sanitized = AiStreamParser.sanitize("bad key sk-abcdefgh12345678 oops");
        assertFalse(sanitized.contains("abcdefgh12345678"));
        assertTrue(sanitized.contains("sk-***"));
    }
}
