package com.tduck.cloud.ai.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * 新版流式接口的 SSE 事件。
 * type: meta / thinking / content / ping / done / error
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiStreamEvent {

    private String type;

    /**
     * thinking / content 事件的增量文本
     */
    private String delta;

    private String code;

    private String message;

    private String requestId;

    private String model;

    private Boolean thinking;

    private String finishReason;

    private Integer contentLength;

    private Integer thinkingLength;

    private Boolean truncated;

    private Boolean historyTrimmed;

    private Boolean retryable;

    private Long timestamp;

    public static AiStreamEvent meta(String requestId, String model, boolean thinking,
                                     boolean historyTrimmed) {
        return AiStreamEvent.builder()
                .type("meta")
                .requestId(requestId)
                .model(model)
                .thinking(thinking)
                .historyTrimmed(historyTrimmed)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static AiStreamEvent thinking(String delta) {
        return AiStreamEvent.builder()
                .type("thinking")
                .delta(delta)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static AiStreamEvent content(String delta) {
        return AiStreamEvent.builder()
                .type("content")
                .delta(delta)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static AiStreamEvent ping() {
        return AiStreamEvent.builder()
                .type("ping")
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static AiStreamEvent done(String requestId, String finishReason,
                                     int contentLength, int thinkingLength, boolean truncated) {
        return AiStreamEvent.builder()
                .type("done")
                .requestId(requestId)
                .finishReason(finishReason)
                .contentLength(contentLength)
                .thinkingLength(thinkingLength)
                .truncated(truncated)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static AiStreamEvent error(String requestId, String code, String message,
                                      boolean retryable) {
        return AiStreamEvent.builder()
                .type("error")
                .requestId(requestId)
                .code(code)
                .message(message)
                .retryable(retryable)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
