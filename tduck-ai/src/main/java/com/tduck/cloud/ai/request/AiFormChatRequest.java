package com.tduck.cloud.ai.request;

import lombok.Data;

import java.util.List;

/**
 * AI 建表 / 表单修正请求。
 */
@Data
public class AiFormChatRequest {

    /**
     * 客户端生成的追踪号，可用于日志定位
     */
    private String requestId;

    /**
     * 对话历史，只允许 user / assistant
     */
    private List<AiChatMessage> messages;

    /**
     * 当前表单快照，历史被裁剪后作为兜底上下文
     */
    private String currentForm;

    /**
     * 是否使用深入思考模型
     */
    private Boolean thinking;

    /**
     * create：新建表单；revise：修改现有表单
     */
    private String mode;

    /**
     * clarify：澄清提问；plan：输出设计方案；generate：生成完整表单（默认）
     */
    private String stage;

    /**
     * 已确认或上一版设计方案 JSON，生成阶段用于约束题目结构
     */
    private String planJson;

    /**
     * revise 模式必填，用于权限校验
     */
    private String formKey;
}
