package com.tduck.cloud.ai.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话消息。只允许 user / assistant，system 由服务端注入。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiChatMessage {

    private String role;

    private String content;
}
