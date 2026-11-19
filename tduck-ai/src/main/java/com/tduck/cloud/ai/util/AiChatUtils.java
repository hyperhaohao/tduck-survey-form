package com.tduck.cloud.ai.util;

import cn.hutool.ai.core.AIService;
import cn.hutool.ai.core.Message;
import cn.hutool.core.io.resource.ClassPathResource;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tduck.cloud.ai.config.AiConfiguration;
import com.tduck.cloud.ai.config.AiProperties;
import com.tduck.cloud.ai.vo.AiChatResponse;
import com.tduck.cloud.ai.vo.AiChatResult;
import com.tduck.cloud.ai.vo.AiStreamEvent;
import com.tduck.cloud.common.exception.BaseException;
import com.tduck.cloud.common.util.JsonUtils;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StreamUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 聊天工具类。
 *
 * <p>负责把 hutool 的流式回调转换为 SSE 事件，并在未配置、上游报错、
 * 连接提前结束时给出明确错误，而不是静默断开。</p>
 */
@UtilityClass
@Slf4j
public class AiChatUtils {

    private static final long HEARTBEAT_SECONDS = 15L;
    private static final long WATCHDOG_EXTRA_MS = 30_000L;

    private static final ScheduledExecutorService HEARTBEAT =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "ai-sse-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * 获取提示词内容
     *
     * @param fileName 提示词文件名（相对于 classpath）
     * @return 提示词内容
     */
    public String getPromptContent(String fileName) {
        try {
            ClassPathResource classPathResource = new ClassPathResource(fileName);
            return StreamUtils.copyToString(
                    classPathResource.getStream(),
                    StandardCharsets.UTF_8
            );
        } catch (Exception e) {
            log.error("AiChatUtils getPromptContent exception, fileName: {}", fileName, e);
            throw new RuntimeException("读取提示词文件失败: " + fileName, e);
        }
    }

    /**
     * 旧版流式接口：返回原始 OpenAI chunk，供旧缓存前端使用。
     * 无配置或上游异常时返回空流，不再抛 NullPointerException。
     */
    public Flux<AiChatResponse> createChatFlux(List<Message> messages, boolean json) {
        if (!AiConfiguration.isConfigured()) {
            log.warn("AI 流式请求被忽略：AI 服务未配置");
            return Flux.empty();
        }
        AIService service = AiConfiguration.getAiService(json, false);
        if (service == null) {
            log.warn("AI 流式请求被忽略：AI 服务不可用");
            return Flux.empty();
        }
        return Flux.create(sink -> {
            AtomicBoolean finished = new AtomicBoolean(false);
            try {
                service.chat(messages, chunk -> {
                    if (finished.get() || sink.isCancelled()) {
                        return;
                    }
                    if ("data: [DONE]".equals(chunk) || "data:[DONE]".equals(chunk)) {
                        finish(sink, finished);
                        return;
                    }
                    AiChatResponse response = AiChatResponse.build(chunk);
                    if (response.getError() != null) {
                        log.warn("AI 上游返回错误，错误内容已脱敏");
                        finish(sink, finished);
                        return;
                    }
                    boolean last = response.getChoices() != null
                            && !response.getChoices().isEmpty()
                            && StrUtil.isNotBlank(response.getChoices().get(0).getFinishReason());
                    sink.next(response);
                    if (last) {
                        finish(sink, finished);
                    }
                });
            } catch (Exception e) {
                log.warn("AI 流式请求异常：{}", e.getClass().getSimpleName());
                synchronized (finished) {
                    if (!finished.get()) {
                        finished.set(true);
                        sink.error(e);
                    }
                }
            }
        });
    }

    private static void finish(FluxSink<?> sink, AtomicBoolean finished) {
        synchronized (finished) {
            if (finished.compareAndSet(false, true)) {
                sink.complete();
            }
        }
    }

    /**
     * 新版流式接口：结构化事件，包含 meta / thinking / content / ping / done / error。
     *
     * @param messages  完整消息列表（含 system）
     * @param json      是否要求 JSON 输出
     * @param thinking  是否深入思考
     * @param requestId 追踪号
     */
    public Flux<AiStreamEvent> createStreamEvents(List<Message> messages, boolean json,
                                                  boolean thinking, String requestId,
                                                  boolean historyTrimmed) {
        if (!AiConfiguration.isConfigured()) {
            return Flux.just(AiStreamEvent.error(requestId, "AI_NOT_CONFIGURED",
                    StrUtil.blankToDefault(AiConfiguration.getStatus().getMessage(),
                            "AI 服务未配置：请管理员在「系统配置 → AI 配置」中完成配置。"),
                    false));
        }
        AIService service = AiConfiguration.getAiService(json, thinking);
        if (service == null) {
            return Flux.just(AiStreamEvent.error(requestId, "AI_NOT_CONFIGURED",
                    "AI 服务不可用：请管理员检查「系统配置 → AI 配置」。", false));
        }

        return Flux.create(sink -> {
            Object lock = new Object();
            AtomicBoolean finished = new AtomicBoolean(false);
            StringBuilder content = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            long[] lastUpstreamAt = {System.currentTimeMillis()};
            long readTimeoutMs = readTimeoutMillis();
            ScheduledFuture<?>[] heartbeatHolder = new ScheduledFuture<?>[1];
            Runnable cancelHeartbeat = () -> {
                if (heartbeatHolder[0] != null) {
                    heartbeatHolder[0].cancel(false);
                }
            };

            heartbeatHolder[0] = HEARTBEAT.scheduleAtFixedRate(() -> {
                synchronized (lock) {
                    if (finished.get() || sink.isCancelled()) {
                        return;
                    }
                    long idle = System.currentTimeMillis() - lastUpstreamAt[0];
                    if (idle > readTimeoutMs + WATCHDOG_EXTRA_MS) {
                        emitError(sink, lock, finished, requestId, AiStreamParser.AI_TIMEOUT,
                                "AI 响应超时，请重试或关闭深入思考", true, cancelHeartbeat);
                        return;
                    }
                    sink.next(AiStreamEvent.ping());
                }
            }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);

            sink.onDispose(() -> {
                synchronized (lock) {
                    finished.set(true);
                }
                cancelHeartbeat.run();
            });

            emit(sink, lock, finished, AiStreamEvent.meta(requestId,
                    AiConfiguration.getEffectiveModel(thinking), thinking, historyTrimmed));

            try {
                service.chat(messages, raw -> {
                    if (sink.isCancelled()) {
                        return;
                    }
                    synchronized (lock) {
                        if (finished.get()) {
                            return;
                        }
                    }
                    lastUpstreamAt[0] = System.currentTimeMillis();
                    AiStreamParser.ParsedLine parsed = AiStreamParser.parse(raw);
                    if (StrUtil.isNotEmpty(parsed.getReasoning())) {
                        reasoning.append(parsed.getReasoning());
                        emit(sink, lock, finished, AiStreamEvent.thinking(parsed.getReasoning()));
                    }
                    if (StrUtil.isNotEmpty(parsed.getContent())) {
                        content.append(parsed.getContent());
                        emit(sink, lock, finished, AiStreamEvent.content(parsed.getContent()));
                    }
                    if (StrUtil.isNotBlank(parsed.getErrorCode())) {
                        emitError(sink, lock, finished, requestId, parsed.getErrorCode(),
                                friendlyMessage(parsed.getErrorCode(), parsed.getErrorMessage()),
                                parsed.isRetryable(), cancelHeartbeat);
                    } else if (parsed.isDone()) {
                        boolean truncated = "length".equalsIgnoreCase(parsed.getFinishReason());
                        emitDone(sink, lock, finished, requestId,
                                parsed.getFinishReason(), content.length(), reasoning.length(), truncated,
                                cancelHeartbeat);
                    }
                });
            } catch (Exception e) {
                String code = AiStreamParser.classifyError(StrUtil.nullToEmpty(e.getMessage()));
                emitError(sink, lock, finished, requestId, code,
                        friendlyMessage(code, e.getMessage()), AiStreamParser.isRetryable(code),
                        cancelHeartbeat);
                log.warn("AI 流式请求异常 requestId={} code={}", requestId, code);
            }
            // 不在调用线程结束流：由回调的 done/error 或看门狗结束
        });
    }

    private static void emit(FluxSink<AiStreamEvent> sink, Object lock, AtomicBoolean finished,
                             AiStreamEvent event) {
        synchronized (lock) {
            if (finished.get() || sink.isCancelled()) {
                return;
            }
            sink.next(event);
        }
    }

    private static void emitDone(FluxSink<AiStreamEvent> sink, Object lock, AtomicBoolean finished,
                                 String requestId, String finishReason, int contentLength,
                                 int thinkingLength, boolean truncated, Runnable onFinish) {
        synchronized (lock) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            sink.next(AiStreamEvent.done(requestId, finishReason, contentLength,
                    thinkingLength, truncated));
            sink.complete();
        }
        onFinish.run();
    }

    private static void emitError(FluxSink<AiStreamEvent> sink, Object lock, AtomicBoolean finished,
                                  String requestId, String code, String message,
                                  boolean retryable, Runnable onFinish) {
        synchronized (lock) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            sink.next(AiStreamEvent.error(requestId, code, message, retryable));
            sink.complete();
        }
        onFinish.run();
    }

    private static String friendlyMessage(String code, String raw) {
        String detail = AiStreamParser.sanitize(raw);
        switch (code) {
            case AiStreamParser.AI_AUTH_FAILED:
                return "API Key 无效或无权限，请管理员检查 AI 配置。";
            case AiStreamParser.AI_MODEL_NOT_FOUND:
                return "模型不可用：旧模型 deepseek-chat/deepseek-reasoner 已停用，请改用 deepseek-v4-flash 或 deepseek-v4-pro。";
            case AiStreamParser.AI_RATE_LIMITED:
                return "AI 服务繁忙或额度不足，请稍后重试。";
            case AiStreamParser.AI_TIMEOUT:
                return "AI 响应超时，可重试或关闭深入思考。";
            case AiStreamParser.AI_NETWORK_ERROR:
                return "无法连接 AI 服务，请检查网络或 Base URL。";
            default:
                return StrUtil.isBlank(detail) ? "AI 服务返回错误，请稍后重试。" : "AI 服务返回错误：" + detail;
        }
    }

    private static long readTimeoutMillis() {
        AiProperties props = AiConfiguration.getAiProperties();
        int seconds = props == null ? 300 : props.getTimeout();
        return com.tduck.cloud.ai.config.AiConfigSupport.timeoutMillis(seconds);
    }

    /**
     * 同步调用，供测试连接与排查接口使用。
     */
    public AiChatResult createChat(List<Message> messages, boolean json) {
        if (!AiConfiguration.isConfigured()) {
            throw new BaseException(StrUtil.blankToDefault(
                    AiConfiguration.getStatus().getMessage(), "AI 服务未配置"), 503);
        }
        AIService aiService = AiConfiguration.getAiService(json, false);
        if (aiService == null) {
            throw new BaseException("AI 服务不可用，请管理员检查 AI 配置", 503);
        }
        try {
            String res = aiService.chat(messages);
            if (StrUtil.isBlank(res)) {
                return new AiChatResult();
            }
            JSONObject jsonObject = JSONUtil.parseObj(res);
            if (jsonObject.get("error") != null) {
                String code = AiStreamParser.classifyError(jsonObject.getStr("error"));
                throw new BaseException(friendlyMessage(code, jsonObject.getStr("error")), 502);
            }
            return JsonUtils.jsonToObj(res, AiChatResult.class);
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            String code = AiStreamParser.classifyError(StrUtil.nullToEmpty(e.getMessage()));
            log.warn("AI 同步请求异常：{}", e.getClass().getSimpleName());
            throw new BaseException(friendlyMessage(code, e.getMessage()), 502);
        }
    }
}
