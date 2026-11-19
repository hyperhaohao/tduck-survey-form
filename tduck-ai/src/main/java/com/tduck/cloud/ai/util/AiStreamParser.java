package com.tduck.cloud.ai.util;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.Data;

import java.util.regex.Pattern;

/**
 * 解析 hutool AI 回调收到的原始 SSE 行。
 * 不记录原始内容，错误文本会脱敏并截断。
 */
public final class AiStreamParser {

    private static final Pattern API_KEY_PATTERN = Pattern.compile("sk-[A-Za-z0-9_-]{8,}");
    private static final int MAX_ERROR_LENGTH = 300;

    public static final String AI_AUTH_FAILED = "AI_AUTH_FAILED";
    public static final String AI_MODEL_NOT_FOUND = "AI_MODEL_NOT_FOUND";
    public static final String AI_RATE_LIMITED = "AI_RATE_LIMITED";
    public static final String AI_TIMEOUT = "AI_TIMEOUT";
    public static final String AI_NETWORK_ERROR = "AI_NETWORK_ERROR";
    public static final String AI_UPSTREAM_ERROR = "AI_UPSTREAM_ERROR";

    private AiStreamParser() {
    }

    public static ParsedLine parse(String rawLine) {
        ParsedLine line = new ParsedLine();
        if (StrUtil.isBlank(rawLine)) {
            return line;
        }
        String trimmed = rawLine.trim();
        if (trimmed.startsWith(":")
                || trimmed.startsWith("event:")
                || trimmed.startsWith("id:")
                || trimmed.startsWith("retry:")) {
            return line;
        }
        String json = trimmed;
        if (json.startsWith("data:")) {
            json = json.substring("data:".length()).trim();
        }
        if ("[DONE]".equals(json)) {
            line.setDone(true);
            line.setFinishReason("stop");
            return line;
        }
        if (!json.startsWith("{")) {
            return line;
        }
        try {
            JSONObject obj = JSONUtil.parseObj(json);
            Object error = obj.get("error");
            if (error != null) {
                String message = errorMessage(error);
                line.setErrorCode(classifyError(message));
                line.setErrorMessage(sanitize(message));
                line.setRetryable(isRetryable(line.getErrorCode()));
                line.setDone(true);
                return line;
            }
            JSONArray choices = obj.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                return line;
            }
            JSONObject choice = choices.getJSONObject(0);
            String finishReason = choice.getStr("finish_reason");
            if (StrUtil.isNotBlank(finishReason)) {
                line.setFinishReason(finishReason);
                line.setDone(true);
            }
            JSONObject delta = choice.getJSONObject("delta");
            if (delta != null) {
                line.setContent(delta.getStr("content"));
                line.setReasoning(delta.getStr("reasoning_content"));
            }
        } catch (Exception e) {
            // 流式输出中可能存在被截断的 JSON 片段，忽略即可
        }
        return line;
    }

    private static String errorMessage(Object error) {
        if (error instanceof CharSequence) {
            return error.toString();
        }
        try {
            JSONObject obj = JSONUtil.parseObj(error);
            String message = obj.getStr("message");
            return StrUtil.blankToDefault(message, error.toString());
        } catch (Exception e) {
            return error.toString();
        }
    }

    public static String classifyError(String message) {
        String text = StrUtil.nullToEmpty(message).toLowerCase();
        if (text.contains("401") || text.contains("403")
                || text.contains("invalid api key") || text.contains("unauthorized")) {
            return AI_AUTH_FAILED;
        }
        if (text.contains("404")
                || (text.contains("model") && (text.contains("not exist") || text.contains("does not exist")))) {
            return AI_MODEL_NOT_FOUND;
        }
        if (text.contains("429") || text.contains("rate limit")
                || text.contains("quota") || text.contains("insufficient balance")) {
            return AI_RATE_LIMITED;
        }
        if (text.contains("timeout") || text.contains("timed out")) {
            return AI_TIMEOUT;
        }
        if (text.contains("unknownhost") || text.contains("connection refused")
                || text.contains("connect timed out") || text.contains("ssl")
                || text.contains("pkix")) {
            return AI_NETWORK_ERROR;
        }
        return AI_UPSTREAM_ERROR;
    }

    public static boolean isRetryable(String code) {
        return AI_RATE_LIMITED.equals(code)
                || AI_TIMEOUT.equals(code)
                || AI_NETWORK_ERROR.equals(code)
                || AI_UPSTREAM_ERROR.equals(code);
    }

    public static String sanitize(String message) {
        String text = StrUtil.nullToEmpty(message);
        text = API_KEY_PATTERN.matcher(text).replaceAll("sk-***");
        if (text.length() > MAX_ERROR_LENGTH) {
            text = text.substring(0, MAX_ERROR_LENGTH) + "...";
        }
        return text;
    }

    /**
     * 单行解析结果。
     */
    @Data
    public static class ParsedLine {
        private String content;
        private String reasoning;
        private boolean done;
        private String finishReason;
        private String errorCode;
        private String errorMessage;
        private boolean retryable;
    }
}
