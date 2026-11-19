package com.tduck.cloud.ai.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;
import lombok.ToString;

/**
 * ai配置
 *
 * @author tduck
 * @since 2025-3-31 17:52:01
 */
@Data
public class AiProperties {

    /**
     * 基础地址，例如 https://api.deepseek.com/v1
     */
    public String baseUrl = "https://api.deepseek.com/v1";
    /**
     * api key，仅在服务端数据库配置中保存，禁止输出到日志
     */
    @ToString.Exclude
    public String apiKey;

    /**
     * 普通模式模型名称
     */
    @JsonAlias("model")
    public String modelName = AiConfigSupport.DEFAULT_MODEL;

    /**
     * 深入思考模式模型名称
     */
    public String reasoningModelName = AiConfigSupport.DEFAULT_REASONING_MODEL;

    /**
     * 深入思考强度：low / high / max，仅官方 DeepSeek 域名下发
     */
    public String reasoningEffort = "high";

    /**
     * 连接与流式读取超时，单位秒
     */
    public int timeout = 300;

    public boolean enabled;


}
