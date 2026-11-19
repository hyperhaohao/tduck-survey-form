package com.tduck.cloud.ai.config;

import cn.hutool.ai.AIServiceFactory;
import cn.hutool.ai.ModelName;
import cn.hutool.ai.core.AIConfig;
import cn.hutool.ai.core.AIConfigBuilder;
import cn.hutool.ai.core.AIService;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.tduck.cloud.ai.vo.AiStatusVO;
import com.tduck.cloud.common.util.JsonUtils;
import com.tduck.cloud.common.util.SpringContextUtils;
import com.tduck.cloud.envconfig.constant.ConfigConstants;
import com.tduck.cloud.envconfig.service.SysEnvConfigService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;

/**
 * AI 服务配置与实例管理。
 *
 * <p>配置来自数据库 sys_env_config 的 deepSeekEnvConfig。保存配置后由
 * EnvConfigRefreshEvent 触发重新构建，无需重启应用。</p>
 */
@Slf4j
public class AiConfiguration {

    @Getter
    private static volatile AiProperties aiProperties;

    private static volatile AIService aiServiceJson;
    private static volatile AIService aiServiceText;
    private static volatile AIService aiServiceJsonThinking;
    private static volatile AIService aiServiceTextThinking;

    private static volatile boolean initialized = false;

    @Getter
    private static volatile String lastError;

    static {
        try {
            buildDeepSeek();
        } catch (Throwable t) {
            initialized = true;
            lastError = "AI 配置初始化失败，请在系统设置中重新保存 AI 配置";
            log.warn("AI 配置初始化失败，等待配置保存后重试");
        }
    }

    /**
     * 从数据库读取配置并重建服务。异常不外抛，结果记录在 lastError。
     */
    public static synchronized void buildDeepSeek() {
        try {
            SysEnvConfigService service = SpringContextUtils.getBean(SysEnvConfigService.class);
            String configJson = service == null
                    ? null
                    : service.getValueByKey(ConfigConstants.DEEP_SEEK_ENV_CONFIG);
            AiProperties props = StrUtil.isBlank(configJson)
                    ? null
                    : JsonUtils.jsonToObj(configJson, AiProperties.class);
            apply(props);
        } catch (Exception e) {
            clearServices("AI 配置解析失败，请在系统设置中重新保存 AI 配置");
            log.warn("AI 配置读取失败：{}", e.getClass().getSimpleName());
        } finally {
            initialized = true;
        }
    }

    /**
     * 应用配置。配置为空、未启用、缺 Key、URL 非法时清空旧服务，避免继续使用过期配置。
     * 包内可见，便于单元测试直接调用。
     */
    public static synchronized void apply(AiProperties props) {
        clearServices(null);
        aiProperties = props;
        if (props == null) {
            lastError = "AI 服务未配置：请管理员在「系统配置 → AI 配置」中启用服务并填写 API Key。";
            return;
        }
        if (!props.isEnabled()) {
            lastError = "AI 服务未启用：请管理员在「系统配置 → AI 配置」中打开启用开关。";
            return;
        }
        String apiKey = StrUtil.trim(props.getApiKey());
        if (StrUtil.isBlank(apiKey)) {
            lastError = "AI 服务缺少 API Key：请在「系统配置 → AI 配置」中填写。";
            return;
        }
        String baseUrl = AiConfigSupport.normalizeBaseUrl(props.getBaseUrl());
        if (baseUrl == null) {
            lastError = "AI 服务 Base URL 不合法：请填写 http(s) 地址，例如 https://api.deepseek.com/v1。";
            return;
        }
        props.setApiKey(apiKey);
        props.setBaseUrl(baseUrl);
        try {
            aiServiceJson = build(props, baseUrl, true, false);
            aiServiceText = build(props, baseUrl, false, false);
            aiServiceJsonThinking = build(props, baseUrl, true, true);
            aiServiceTextThinking = build(props, baseUrl, false, true);
            lastError = null;
            log.info("AI 配置已加载 host={} model={} reasoningModel={} thinkingMode={}",
                    host(baseUrl),
                    AiConfigSupport.resolveModelName(props, false),
                    AiConfigSupport.resolveModelName(props, true),
                    AiConfigSupport.isOfficialDeepSeek(baseUrl) ? "native" : "model");
        } catch (Exception e) {
            clearServices("AI 服务初始化失败，请检查 Base URL 与模型名称");
            log.warn("AI 服务初始化失败：{}", e.getClass().getSimpleName());
        }
    }

    private static AIService build(AiProperties props, String baseUrl, boolean json, boolean thinking) {
        boolean official = AiConfigSupport.isOfficialDeepSeek(baseUrl);
        AIConfigBuilder builder = new AIConfigBuilder(ModelName.DEEPSEEK.getValue())
                .setApiKey(props.getApiKey())
                .setApiUrl(baseUrl)
                .setModel(AiConfigSupport.resolveModelName(props, thinking))
                .setTimeout(AiConfigSupport.timeoutMillis(props.getTimeout()))
                .setReadTimeout(AiConfigSupport.timeoutMillis(props.getTimeout()));
        if (json && official) {
            builder.putAdditionalConfig("response_format", MapUtil.of("type", "json_object"));
        }
        if (official) {
            builder.putAdditionalConfig("thinking", MapUtil.of("type", thinking ? "enabled" : "disabled"));
            if (thinking) {
                builder.putAdditionalConfig("reasoning_effort",
                        AiConfigSupport.normalizeEffort(props.getReasoningEffort()));
            }
        }
        AIConfig config = builder.build();
        return AIServiceFactory.getAIService(config);
    }

    private static void clearServices(String error) {
        aiServiceJson = null;
        aiServiceText = null;
        aiServiceJsonThinking = null;
        aiServiceTextThinking = null;
        lastError = error;
    }

    /**
     * 获取服务实例；未配置时返回 null，由调用方转换为明确的错误提示。
     */
    public static AIService getAiService(boolean json, boolean thinking) {
        ensureInitialized();
        if (thinking) {
            return json ? aiServiceJsonThinking : aiServiceTextThinking;
        }
        return json ? aiServiceJson : aiServiceText;
    }

    public static boolean isConfigured() {
        ensureInitialized();
        return aiServiceJson != null;
    }

    public static String getEffectiveModel(boolean thinking) {
        return AiConfigSupport.resolveModelName(aiProperties, thinking);
    }

    public static AiStatusVO getStatus() {
        ensureInitialized();
        AiProperties props = aiProperties;
        boolean enabled = props != null && props.isEnabled();
        boolean configured = aiServiceJson != null;
        boolean official = props != null && AiConfigSupport.isOfficialDeepSeek(
                AiConfigSupport.normalizeBaseUrl(props.getBaseUrl()));
        return AiStatusVO.builder()
                .enabled(enabled)
                .configured(configured)
                .official(official)
                .modelName(AiConfigSupport.resolveModelName(props, false))
                .reasoningModelName(AiConfigSupport.resolveModelName(props, true))
                .reasoningEffort(AiConfigSupport.normalizeEffort(props == null ? null : props.getReasoningEffort()))
                .thinkingMode(configured ? (official ? "native" : "model") : "none")
                .supportsThinking(configured)
                .message(configured
                        ? "AI 服务已就绪"
                        : StrUtil.blankToDefault(lastError, "AI 服务未配置：请管理员在「系统配置 → AI 配置」中完成配置。"))
                .build();
    }

    private static void ensureInitialized() {
        if (!initialized) {
            synchronized (AiConfiguration.class) {
                if (!initialized) {
                    try {
                        buildDeepSeek();
                    } catch (Exception ignored) {
                        initialized = true;
                    }
                }
            }
        }
    }

    private static String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }
}
