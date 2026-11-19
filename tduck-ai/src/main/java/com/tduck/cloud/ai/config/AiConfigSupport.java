package com.tduck.cloud.ai.config;

import cn.hutool.core.util.StrUtil;

import java.net.URI;

/**
 * AI 配置纯工具类，不依赖 Spring，便于单元测试。
 */
public final class AiConfigSupport {

    public static final String DEFAULT_MODEL = "deepseek-v4-flash";
    public static final String DEFAULT_REASONING_MODEL = "deepseek-v4-pro";
    public static final String LEGACY_CHAT_MODEL = "deepseek-chat";
    public static final String LEGACY_REASONER_MODEL = "deepseek-reasoner";

    private AiConfigSupport() {
    }

    /**
     * 归一化 Base URL：去空白、去尾部斜杠、去掉误填的 /chat/completions；
     * 只允许 http/https，且不允许 userInfo、query、fragment。
     *
     * @return 合法地址或 null
     */
    public static String normalizeBaseUrl(String raw) {
        String url = StrUtil.trim(raw);
        if (StrUtil.isBlank(url)) {
            return null;
        }
        if (url.endsWith("/chat/completions")) {
            url = url.substring(0, url.length() - "/chat/completions".length());
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return null;
            }
            if (StrUtil.isBlank(uri.getHost())) {
                return null;
            }
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                return null;
            }
            return url;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 严格判断是否为 DeepSeek 官方域名，避免 api.deepseek.com.evil.com 被误判。
     */
    public static boolean isOfficialDeepSeek(String url) {
        if (StrUtil.isBlank(url)) {
            return false;
        }
        try {
            return "api.deepseek.com".equalsIgnoreCase(URI.create(url).getHost());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 解析实际使用的模型名称，并迁移已经停用的旧别名。
     */
    public static String resolveModelName(AiProperties props, boolean thinking) {
        String configured = props == null
                ? null
                : (thinking ? props.getReasoningModelName() : props.getModelName());
        String model = StrUtil.trim(configured);
        if (StrUtil.isBlank(model)) {
            model = thinking ? DEFAULT_REASONING_MODEL : DEFAULT_MODEL;
        }
        if (LEGACY_CHAT_MODEL.equalsIgnoreCase(model)) {
            return DEFAULT_MODEL;
        }
        if (LEGACY_REASONER_MODEL.equalsIgnoreCase(model)) {
            return DEFAULT_REASONING_MODEL;
        }
        return model;
    }

    /**
     * 归一化思考强度，非法值回退 high。
     */
    public static String normalizeEffort(String effort) {
        String value = StrUtil.trim(effort);
        if ("low".equalsIgnoreCase(value) || "max".equalsIgnoreCase(value) || "high".equalsIgnoreCase(value)) {
            return value.toLowerCase();
        }
        return "high";
    }

    /**
     * 秒转毫秒，限制在 30-600 秒之间。
     */
    public static int timeoutMillis(int seconds) {
        int value = seconds;
        if (value < 30) {
            value = 30;
        }
        if (value > 600) {
            value = 600;
        }
        return value * 1000;
    }
}
