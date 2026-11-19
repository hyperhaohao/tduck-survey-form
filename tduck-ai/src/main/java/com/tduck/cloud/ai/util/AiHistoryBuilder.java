package com.tduck.cloud.ai.util;

import cn.hutool.ai.core.Message;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.tduck.cloud.ai.request.AiChatMessage;
import com.tduck.cloud.common.exception.BaseException;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 构建发送给模型的完整消息列表，负责角色校验、历史裁剪与长度限制。
 */
public final class AiHistoryBuilder {

    public static final int MAX_MESSAGES = 20;
    public static final int MAX_TOTAL_CHARS = 100000;
    public static final int MAX_USER_CHARS = 4000;
    public static final int MAX_ASSISTANT_CHARS = 60000;

    private AiHistoryBuilder() {
    }

    public static Result build(String systemPrompt, List<AiChatMessage> history, String currentForm) {
        return build(systemPrompt, history, currentForm, null);
    }

    public static Result build(String systemPrompt, List<AiChatMessage> history, String currentForm,
                               String planJson) {
        if (history == null || history.isEmpty()) {
            throw new BaseException("消息不能为空", 400);
        }
        List<AiChatMessage> msgs = new ArrayList<>();
        for (AiChatMessage item : history) {
            if (item == null || StrUtil.isBlank(item.getContent())) {
                continue;
            }
            String role = StrUtil.trim(item.getRole());
            if (!"user".equals(role) && !"assistant".equals(role)) {
                throw new BaseException("不支持的消息角色", 400);
            }
            String content;
            if ("user".equals(role)) {
                content = item.getContent().trim();
                if (content.length() > MAX_USER_CHARS) {
                    throw new BaseException("单条消息过长，请精简后重试", 400);
                }
            } else {
                content = normalizeJson(item.getContent());
                if (content == null) {
                    // 非法 assistant 历史直接丢弃，避免污染上下文
                    continue;
                }
            }
            msgs.add(new AiChatMessage(role, content));
        }
        if (msgs.isEmpty() || !"user".equals(msgs.get(msgs.size() - 1).getRole())) {
            throw new BaseException("最后一条消息必须是用户指令", 400);
        }

        String form = normalizeJson(currentForm);
        String plan = normalizeJson(planJson);
        boolean trimmed = trimMessages(msgs, form, plan);
        msgs = mergeConsecutive(msgs);

        int total = chars(msgs) + (form == null ? 0 : form.length())
                + (plan == null ? 0 : plan.length());
        if (total > MAX_TOTAL_CHARS) {
            throw new BaseException("对话内容过长，请点击「重新开始」后继续", 400);
        }

        List<Message> out = new ArrayList<>();
        out.add(new Message("system", systemPrompt));
        if (form != null) {
            out.add(new Message("user", "以下是当前表单的最新 JSON，请以此为修改基础：\n" + form));
        }
        if (plan != null) {
            out.add(new Message("user", "以下是上一版/已确认的设计方案 JSON，请以它为约束：\n" + plan));
        }
        for (AiChatMessage item : msgs) {
            out.add(new Message(item.getRole(), item.getContent()));
        }
        return new Result(out, trimmed);
    }

    /**
     * 历史裁剪：超过 20 条时保留首条与最近消息；超长时压缩为首条 + 当前表单/方案 + 最后一条。
     */
    private static boolean trimMessages(List<AiChatMessage> msgs, String form, String plan) {
        boolean trimmed = false;
        if (msgs.size() > MAX_MESSAGES) {
            AiChatMessage first = msgs.get(0);
            List<AiChatMessage> tail = new ArrayList<>(
                    msgs.subList(msgs.size() - (MAX_MESSAGES - 1), msgs.size()));
            msgs.clear();
            msgs.add(first);
            msgs.addAll(tail);
            trimmed = true;
        }
        int total = chars(msgs) + (form == null ? 0 : form.length())
                + (plan == null ? 0 : plan.length());
        if (total > MAX_TOTAL_CHARS) {
            AiChatMessage first = msgs.get(0);
            AiChatMessage last = msgs.get(msgs.size() - 1);
            msgs.clear();
            msgs.add(first);
            if (last != first) {
                msgs.add(last);
            }
            trimmed = true;
        }
        return trimmed;
    }

    private static List<AiChatMessage> mergeConsecutive(List<AiChatMessage> msgs) {
        List<AiChatMessage> merged = new ArrayList<>();
        for (AiChatMessage item : msgs) {
            if (!merged.isEmpty()
                    && merged.get(merged.size() - 1).getRole().equals(item.getRole())) {
                AiChatMessage last = merged.get(merged.size() - 1);
                last.setContent(last.getContent() + "\n\n" + item.getContent());
            } else {
                merged.add(new AiChatMessage(item.getRole(), item.getContent()));
            }
        }
        return merged;
    }

    /**
     * 规范化 assistant 历史或当前表单：去除代码围栏、截取 JSON 主体并校验。
     */
    public static String normalizeJson(String content) {
        String text = StrUtil.trim(content);
        if (StrUtil.isBlank(text)) {
            return null;
        }
        text = text.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        int arrayStart = text.indexOf('[');
        int objectStart = text.indexOf('{');
        try {
            if (arrayStart >= 0 && (objectStart < 0 || arrayStart < objectStart)) {
                int end = text.lastIndexOf(']');
                if (end <= arrayStart) {
                    return null;
                }
                text = text.substring(arrayStart, end + 1);
                JSONUtil.parseArray(text);
            } else if (objectStart >= 0) {
                int end = text.lastIndexOf('}');
                if (end <= objectStart) {
                    return null;
                }
                text = text.substring(objectStart, end + 1);
                JSONUtil.parseObj(text);
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        if (text.length() > MAX_ASSISTANT_CHARS) {
            return null;
        }
        return text;
    }

    private static int chars(List<AiChatMessage> msgs) {
        int total = 0;
        for (AiChatMessage item : msgs) {
            total += item.getContent() == null ? 0 : item.getContent().length();
        }
        return total;
    }

    /**
     * 构建结果，historyTrimmed 用于前端提示历史已被裁剪。
     */
    @Getter
    public static class Result {
        private final List<Message> messages;
        private final boolean historyTrimmed;

        public Result(List<Message> messages, boolean historyTrimmed) {
            this.messages = messages;
            this.historyTrimmed = historyTrimmed;
        }
    }
}
