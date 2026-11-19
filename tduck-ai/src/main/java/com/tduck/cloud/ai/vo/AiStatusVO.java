package com.tduck.cloud.ai.vo;

import lombok.Builder;
import lombok.Data;

/**
 * AI 服务状态，供前端判断是否已配置以及是否支持深入思考。
 * 不返回 API Key 和完整 Base URL。
 */
@Data
@Builder
public class AiStatusVO {

    /**
     * 是否已打开启用开关
     */
    private boolean enabled;

    /**
     * 是否已正确配置并构建出服务
     */
    private boolean configured;

    /**
     * 是否官方 DeepSeek 域名
     */
    private boolean official;

    private String modelName;

    private String reasoningModelName;

    private String reasoningEffort;

    /**
     * native：官方域名下发 thinking 参数；model：非官方网关只切换模型名；none：未配置
     */
    private String thinkingMode;

    private boolean supportsThinking;

    /**
     * 未配置时的中文原因，前端直接展示
     */
    private String message;
}
