package com.tduck.cloud.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.hutool.ai.core.Message;
import com.tduck.cloud.ai.request.AiChatMessage;
import com.tduck.cloud.ai.util.AiHistoryBuilder;
import com.tduck.cloud.common.exception.BaseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiHistoryBuilderTest {

    private final String system = "system prompt";

    @Test
    void buildsSystemAndHistory() {
        List<AiChatMessage> history = Arrays.asList(
                new AiChatMessage("user", "生成一份问卷"),
                new AiChatMessage("assistant", "```json\n[{\"type\":\"单选\",\"title\":\"题1\"}]\n```"),
                new AiChatMessage("user", "把第1题改成多选"));
        AiHistoryBuilder.Result result = AiHistoryBuilder.build(system, history, null);

        assertFalse(result.isHistoryTrimmed());
        List<Message> messages = result.getMessages();
        assertEquals(4, messages.size());
        assertEquals("system", messages.get(0).getRole());
        assertEquals("user", messages.get(3).getRole());
        assertTrue(messages.get(2).getContent().toString().startsWith("["));
    }

    @Test
    void invalidRoleAndLastMessageRejected() {
        assertThrows(BaseException.class, () -> AiHistoryBuilder.build(system,
                Arrays.asList(new AiChatMessage("system", "x"), new AiChatMessage("user", "y")), null));
        assertThrows(BaseException.class, () -> AiHistoryBuilder.build(system,
                Arrays.asList(new AiChatMessage("user", "x"), new AiChatMessage("assistant", "[]")), null));
        assertThrows(BaseException.class, () -> AiHistoryBuilder.build(system,
                new ArrayList<>(), null));
    }

    @Test
    void invalidAssistantHistoryIsDropped() {
        List<AiChatMessage> history = Arrays.asList(
                new AiChatMessage("user", "生成问卷"),
                new AiChatMessage("assistant", "这不是JSON"),
                new AiChatMessage("user", "继续"));
        AiHistoryBuilder.Result result = AiHistoryBuilder.build(system, history, null);

        List<Message> messages = result.getMessages();
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).getRole());
        assertEquals("user", messages.get(1).getRole());
    }

    @Test
    void tooManyMessagesAreTrimmedAndFirstUserKept() {
        List<AiChatMessage> history = new ArrayList<>();
        history.add(new AiChatMessage("user", "最初需求"));
        for (int i = 0; i < 30; i++) {
            history.add(new AiChatMessage(i % 2 == 0 ? "assistant" : "user",
                    i % 2 == 0 ? "[]" : "第" + i + "轮"));
        }
        history.add(new AiChatMessage("user", "最新指令"));
        AiHistoryBuilder.Result result = AiHistoryBuilder.build(system, history, null);

        assertTrue(result.isHistoryTrimmed());
        assertTrue(result.getMessages().size() <= AiHistoryBuilder.MAX_MESSAGES + 1);
        assertEquals("最初需求", result.getMessages().get(1).getContent());
        assertTrue(result.getMessages().get(result.getMessages().size() - 1)
                .getContent().toString().contains("最新指令"));
    }

    @Test
    void currentFormIsAddedAsContext() {
        List<AiChatMessage> history = Arrays.asList(new AiChatMessage("user", "改第2题"));
        AiHistoryBuilder.Result result = AiHistoryBuilder.build(system, history,
                "{\"title\":\"问卷\",\"questions\":[]}");

        List<Message> messages = result.getMessages();
        assertEquals(3, messages.size());
        assertEquals("system", messages.get(0).getRole());
        assertEquals("user", messages.get(1).getRole());
        assertTrue(messages.get(1).getContent().toString().contains("当前表单"));
    }

    @Test
    void planContextIsAddedBeforeHistory() {
        List<AiChatMessage> history = Arrays.asList(new AiChatMessage("user", "按方案生成"));
        AiHistoryBuilder.Result result = AiHistoryBuilder.build(system, history,
                "{\"title\":\"表单\",\"questions\":[]}",
                "{\"title\":\"方案\",\"outline\":[]}");

        List<Message> messages = result.getMessages();
        assertEquals(4, messages.size());
        assertTrue(messages.get(1).getContent().toString().contains("当前表单"));
        assertTrue(messages.get(2).getContent().toString().contains("设计方案"));
        assertEquals("user", messages.get(3).getRole());
    }

    @Test
    void normalizeJsonHandlesFencesAndInvalidText() {
        assertNotNull(AiHistoryBuilder.normalizeJson("```json\n[{\"a\":1}]\n```"));
        assertNotNull(AiHistoryBuilder.normalizeJson("说明文字 {\"a\":1} 结尾"));
        assertNull(AiHistoryBuilder.normalizeJson("不是 JSON"));
        assertNull(AiHistoryBuilder.normalizeJson("[{\"a\":"));
    }
}
