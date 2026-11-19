package com.tduck.cloud.ai.controller;

import cn.hutool.ai.core.Message;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.tduck.cloud.ai.config.AiConfiguration;
import com.tduck.cloud.ai.constant.MessageRoleConstants;
import com.tduck.cloud.ai.request.AiFormChatRequest;
import com.tduck.cloud.ai.util.AiChatUtils;
import com.tduck.cloud.ai.util.AiHistoryBuilder;
import com.tduck.cloud.ai.vo.AiChatResponse;
import com.tduck.cloud.ai.vo.AiChatResult;
import com.tduck.cloud.ai.vo.AiStatusVO;
import com.tduck.cloud.ai.vo.AiStreamEvent;
import com.tduck.cloud.common.exception.BaseException;
import com.tduck.cloud.common.util.Result;
import com.tduck.cloud.form.util.FormAuthUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 建表与表单修正接口。
 */
@Slf4j
@RequiredArgsConstructor
@RestController
public class AiController {

    private static final int RATE_LIMIT_PER_MINUTE = 12;
    private static final String CREATE_PROMPT = "form-prompt-deepseek.md";
    private static final String REVISE_PROMPT = "form-prompt-revise.md";
    private static final String CLARIFY_PROMPT = "form-prompt-clarify.md";
    private static final String PLAN_PROMPT = "form-prompt-plan.md";

    private final ConcurrentHashMap<Long, long[]> windows = new ConcurrentHashMap<>();

    /**
     * 旧版流式接口，保留给已缓存的前端。
     * 返回原始 OpenAI chunk，model 参数仅为兼容，实际模型由后台配置决定。
     */
    @GetMapping(value = "/ai/gen-form-chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AiChatResponse> genFormChat(@RequestParam String msg,
                                            @RequestParam(required = false) String model,
                                            @RequestAttribute("userId") Long userId,
                                            HttpServletResponse response) {
        sseHeaders(response);
        checkRate(userId);
        if (StrUtil.isBlank(msg)) {
            return Flux.empty();
        }
        String content = AiChatUtils.getPromptContent(CREATE_PROMPT);
        List<Message> messages = CollUtil.newArrayList(
                new Message(MessageRoleConstants.SYSTEM, content),
                new Message(MessageRoleConstants.USER, msg));
        return AiChatUtils.createChatFlux(messages, true);
    }

    /**
     * 新版流式会话接口，支持多轮历史、深入思考与计划先行。
     */
    @PostMapping(value = "/ai/gen-form-chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AiStreamEvent> genFormChatStream(@RequestBody AiFormChatRequest request,
                                                 @RequestAttribute("userId") Long userId,
                                                 HttpServletResponse response) {
        sseHeaders(response);
        checkRate(userId);
        String requestId = requestId(request.getRequestId());
        boolean revise = "revise".equalsIgnoreCase(StrUtil.trim(request.getMode()));
        if (revise) {
            if (StrUtil.isBlank(request.getFormKey())) {
                throw new BaseException("缺少表单标识", 400);
            }
            FormAuthUtils.hasPermission(request.getFormKey());
        }
        String stage = StrUtil.blankToDefault(StrUtil.trim(request.getStage()), "generate").toLowerCase();
        if (!"clarify".equals(stage) && !"plan".equals(stage) && !"generate".equals(stage)) {
            throw new BaseException("不支持的生成阶段", 400);
        }
        String prompt;
        if ("clarify".equals(stage)) {
            prompt = AiChatUtils.getPromptContent(CLARIFY_PROMPT);
        } else if ("plan".equals(stage)) {
            prompt = AiChatUtils.getPromptContent(PLAN_PROMPT);
        } else {
            prompt = AiChatUtils.getPromptContent(revise ? REVISE_PROMPT : CREATE_PROMPT);
        }
        AiHistoryBuilder.Result built = AiHistoryBuilder.build(
                prompt, request.getMessages(), request.getCurrentForm(), request.getPlanJson());
        return AiChatUtils.createStreamEvents(
                built.getMessages(),
                true,
                Boolean.TRUE.equals(request.getThinking()),
                requestId,
                built.isHistoryTrimmed());
    }

    /**
     * AI 服务状态，前端用于判断是否已配置以及是否支持深入思考。
     */
    @GetMapping("/ai/status")
    public Result<AiStatusVO> status(@RequestAttribute(value = "userId", required = false) Long userId) {
        return Result.success(AiConfiguration.getStatus());
    }

    /**
     * 测试生成问卷（同步）。保留用于运维排查，不再公开。
     */
    @GetMapping("/ai/test-gen-form")
    public Result<AiChatResult> testGenForm(@RequestParam String msg,
                                            @RequestAttribute("userId") Long userId) {
        checkRate(userId);
        if (StrUtil.isBlank(msg)) {
            return Result.failed("提示词不能为空");
        }
        String content = AiChatUtils.getPromptContent(CREATE_PROMPT);
        List<Message> messages = CollUtil.newArrayList(
                new Message(MessageRoleConstants.SYSTEM, content),
                new Message(MessageRoleConstants.USER, msg));
        return Result.success(AiChatUtils.createChat(messages, true));
    }

    /**
     * 测试连接：发送极小提示验证 Key 与模型，不返回 Key。
     */
    @GetMapping("/ai/test-connection")
    public Result<Map<String, Object>> testConnection(@RequestAttribute("userId") Long userId) {
        checkRate(userId);
        if (!AiConfiguration.isConfigured()) {
            return Result.failed(AiConfiguration.getStatus().getMessage());
        }
        long start = System.currentTimeMillis();
        AiChatResult result = AiChatUtils.createChat(
                CollUtil.newArrayList(new Message(MessageRoleConstants.USER, "请只回复：OK")), false);
        long cost = System.currentTimeMillis() - start;
        Map<String, Object> data = new HashMap<>();
        data.put("ok", true);
        data.put("model", AiConfiguration.getEffectiveModel(false));
        data.put("ms", cost);
        data.put("message", "连接成功");
        return Result.success(data);
    }

    private void sseHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        response.setCharacterEncoding("UTF-8");
    }

    private String requestId(String raw) {
        String id = StrUtil.trim(raw);
        if (StrUtil.isBlank(id) || !id.matches("[A-Za-z0-9_-]{1,64}")) {
            return "ai-" + System.currentTimeMillis();
        }
        return id;
    }

    /**
     * 轻量限速，防止误循环消耗模型额度。
     */
    private void checkRate(Long userId) {
        long now = System.currentTimeMillis();
        Long key = userId == null ? -1L : userId;
        if (windows.size() > 5000) {
            windows.entrySet().removeIf(entry -> now - entry.getValue()[0] > 60000);
        }
        long[] window = windows.compute(key, (id, value) ->
                value == null || now - value[0] > 60000
                        ? new long[]{now, 1}
                        : new long[]{value[0], value[1] + 1});
        if (window[1] > RATE_LIMIT_PER_MINUTE) {
            throw new BaseException("AI 请求过于频繁，请稍后重试", 429);
        }
    }
}
