package com.tduck.cloud.api.demand;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** 钉钉工作通知与待办的 HTTP 客户端；经典/新版 token 分开缓存。 */
@Slf4j
@Service
public class DemandDingTalkClient {
  private final DemandDingTalkProperties properties;
  private final ObjectMapper mapper;
  private final RestTemplate restTemplate;

  private volatile String workToken;
  private volatile long workTokenExpiresAt;
  private volatile String todoToken;
  private volatile long todoTokenExpiresAt;

  public DemandDingTalkClient(DemandDingTalkProperties properties, ObjectMapper mapper) {
    this.properties = properties;
    this.mapper = mapper;
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Math.max(1000, properties.getConnectTimeoutMs()));
    factory.setReadTimeout(Math.max(2000, properties.getReadTimeoutMs()));
    this.restTemplate = new RestTemplate(factory);
  }

  public String workToken() {
    long now = System.currentTimeMillis();
    String current = workToken;
    if (current != null && now < workTokenExpiresAt) return current;
    synchronized (this) {
      if (workToken != null && now < workTokenExpiresAt) return workToken;
      String url =
          UriComponentsBuilder.fromHttpUrl(properties.getApiBase() + "/gettoken")
              .queryParam("appkey", properties.getAppKey())
              .queryParam("appsecret", properties.getAppSecret())
              .toUriString();
      Map<?, ?> response = get(url);
      Number code = number(response.get("errcode"));
      if (code != null && code.intValue() != 0)
        throw new DemandDingTalkException(
            code.intValue(), str(response.get("errmsg")), isTransientCode(code.intValue()));
      String token = str(response.get("access_token"));
      if (token.isEmpty()) throw new DemandDingTalkException(-1, "gettoken 未返回 access_token", true);
      Number expires = number(response.get("expires_in"));
      long ttl = expires == null ? 7200 : expires.longValue();
      workToken = token;
      workTokenExpiresAt = now + Math.max(300, ttl - 300) * 1000L;
      return token;
    }
  }

  public String todoToken() {
    long now = System.currentTimeMillis();
    String current = todoToken;
    if (current != null && now < todoTokenExpiresAt) return current;
    synchronized (this) {
      if (todoToken != null && now < todoTokenExpiresAt) return todoToken;
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("appKey", properties.getAppKey());
      body.put("appSecret", properties.getAppSecret());
      Map<?, ?> response = postJson(properties.getApiV1Base() + "/v1.0/oauth2/accessToken", body, null);
      String token = str(response.get("accessToken"));
      if (token.isEmpty()) throw new DemandDingTalkException(-1, "accessToken 为空", true);
      Number expires = number(response.get("expireIn"));
      long ttl = expires == null ? 7200 : expires.longValue();
      todoToken = token;
      todoTokenExpiresAt = now + Math.max(300, ttl - 300) * 1000L;
      return token;
    }
  }

  public void invalidateTokens() {
    workToken = null;
    workTokenExpiresAt = 0;
    todoToken = null;
    todoTokenExpiresAt = 0;
  }

  /** 工作通知；userIds 逗号分隔，最多 100 个。 */
  public String sendWork(String userIds, Map<String, Object> message) {
    try {
      return sendWorkOnce(userIds, message);
    } catch (DemandDingTalkException error) {
      if (error.getCode() == 40014 || error.getCode() == 42001) {
        invalidateTokens();
        return sendWorkOnce(userIds, message);
      }
      throw error;
    }
  }

  private String sendWorkOnce(String userIds, Map<String, Object> message) {
    String token = workToken();
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("agent_id", String.valueOf(properties.getAgentId()));
    form.add("userid_list", userIds);
    form.add("to_all_user", "false");
    try {
      form.add("msg", mapper.writeValueAsString(message));
    } catch (Exception error) {
      throw new DemandDingTalkException(-1, "工作通知消息序列化失败", false);
    }
    String url =
        properties.getApiBase()
            + "/topapi/message/corpconversation/asyncsend_v2?access_token="
            + token;
    Map<?, ?> response = postForm(url, form);
    Number code = number(response.get("errcode"));
    if (code != null && code.intValue() != 0)
      throw new DemandDingTalkException(
          code.intValue(), str(response.get("errmsg")), isTransientCode(code.intValue()));
    return str(response.get("task_id"));
  }

  /** 创建钉钉待办；creatorUnionId 为资源所有者，executorUnionId 为执行人。 */
  public String createTodo(
      String creatorUnionId,
      String executorUnionId,
      String subject,
      String description,
      Long dueTime,
      String detailUrl,
      String sourceId) {
    try {
      return createTodoOnce(
          creatorUnionId, executorUnionId, subject, description, dueTime, detailUrl, sourceId);
    } catch (DemandDingTalkException error) {
      if (error.getCode() == 401 || error.getCode() == 40014 || error.getCode() == 42001) {
        invalidateTokens();
        return createTodoOnce(
            creatorUnionId, executorUnionId, subject, description, dueTime, detailUrl, sourceId);
      }
      throw error;
    }
  }

  private String createTodoOnce(
      String creatorUnionId,
      String executorUnionId,
      String subject,
      String description,
      Long dueTime,
      String detailUrl,
      String sourceId) {
    String token = todoToken();
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("appUrl", detailUrl);
    detail.put("pcUrl", detailUrl);
    Map<String, Object> notify = new LinkedHashMap<>();
    notify.put("dingNotify", "0");
    notify.put("sendTodoApn", "false");
    notify.put("sendAssistantChat", "false");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sourceId", sourceId);
    body.put("subject", clip(subject, 200));
    body.put("creatorId", creatorUnionId);
    body.put("description", clip(description, 1000));
    body.put("executorIds", Collections.singletonList(executorUnionId));
    body.put("participantIds", Collections.emptyList());
    body.put("detailUrl", detail);
    body.put("isOnlyShowExecutor", true);
    body.put("priority", 20);
    body.put("notifyConfigs", notify);
    if (dueTime != null && dueTime > 0) body.put("dueTime", dueTime);
    String url =
        properties.getApiV1Base()
            + "/v1.0/todo/users/"
            + creatorUnionId
            + "/tasks";
    Map<?, ?> response = postJson(url, body, token);
    String taskId = str(response.get("id"));
    if (taskId.isEmpty()) taskId = str(response.get("taskId"));
    if (taskId.isEmpty())
      throw new DemandDingTalkException(-1, "钉钉待办未返回任务 ID", true);
    return taskId;
  }

  public void completeTodo(String creatorUnionId, String taskId) {
    String token = todoToken();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("done", true);
    String url =
        properties.getApiV1Base()
            + "/v1.0/todo/users/"
            + creatorUnionId
            + "/tasks/"
            + taskId
            + "?operatorId="
            + creatorUnionId;
    putJson(url, body, token);
  }

  public void deleteTodo(String creatorUnionId, String taskId) {
    String token = todoToken();
    String url =
        properties.getApiV1Base()
            + "/v1.0/todo/users/"
            + creatorUnionId
            + "/tasks/"
            + taskId;
    delete(url, token);
  }

  public List<Map<String, Object>> listSubDepartments(long deptId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("dept_id", deptId);
    body.put("language", "zh_CN");
    Map<?, ?> response = postForm(workUrl("/topapi/v2/department/listsub"), form(body));
    Number code = number(response.get("errcode"));
    if (code != null && code.intValue() != 0)
      throw new DemandDingTalkException(code.intValue(), str(response.get("errmsg")), false);
    return maps(response.get("result"));
  }

  public Map<String, Object> listUsers(long deptId, long cursor, int size) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("dept_id", deptId);
    body.put("cursor", cursor);
    body.put("size", size);
    body.put("contain_access_limit", false);
    body.put("language", "zh_CN");
    Map<?, ?> response = postForm(workUrl("/topapi/v2/user/list"), form(body));
    Number code = number(response.get("errcode"));
    if (code != null && code.intValue() != 0)
      throw new DemandDingTalkException(code.intValue(), str(response.get("errmsg")), false);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("userlist", maps(response.get("result") instanceof Map
        ? ((Map<?, ?>) response.get("result")).get("list")
        : null));
    Map<?, ?> map = response.get("result") instanceof Map
        ? (Map<?, ?>) response.get("result")
        : Collections.emptyMap();
    result.put("has_more", map.get("has_more"));
    result.put("next_cursor", map.get("next_cursor"));
    return result;
  }

  private String workUrl(String path) {
    return properties.getApiBase() + path + "?access_token=" + workToken();
  }

  private MultiValueMap<String, String> form(Map<String, Object> values) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    for (Map.Entry<String, Object> entry : values.entrySet())
      if (entry.getValue() != null) form.add(entry.getKey(), entry.getValue().toString());
    return form;
  }

  private Map<?, ?> get(String url) {
    try {
      return restTemplate.getForObject(url, Map.class);
    } catch (HttpStatusCodeException error) {
      throw httpError(error);
    } catch (Exception error) {
      throw new DemandDingTalkException(-1, "钉钉请求失败：" + error.getClass().getSimpleName(), true);
    }
  }

  private Map<?, ?> postForm(String url, MultiValueMap<String, String> form) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    try {
      return restTemplate.postForObject(url, new HttpEntity<>(form, headers), Map.class);
    } catch (HttpStatusCodeException error) {
      throw httpError(error);
    } catch (Exception error) {
      throw new DemandDingTalkException(-1, "钉钉请求失败：" + error.getClass().getSimpleName(), true);
    }
  }

  private Map<?, ?> postJson(String url, Map<String, Object> body, String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (token != null) headers.set("x-acs-dingtalk-access-token", token);
    try {
      return restTemplate.postForObject(url, new HttpEntity<>(body, headers), Map.class);
    } catch (HttpStatusCodeException error) {
      throw httpError(error);
    } catch (Exception error) {
      throw new DemandDingTalkException(-1, "钉钉请求失败：" + error.getClass().getSimpleName(), true);
    }
  }

  private void putJson(String url, Map<String, Object> body, String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("x-acs-dingtalk-access-token", token);
    try {
      restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(body, headers), Map.class);
    } catch (HttpStatusCodeException error) {
      throw httpError(error);
    } catch (Exception error) {
      throw new DemandDingTalkException(-1, "钉钉请求失败：" + error.getClass().getSimpleName(), true);
    }
  }

  private void delete(String url, String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.set("x-acs-dingtalk-access-token", token);
    try {
      restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(null, headers), Map.class);
    } catch (HttpStatusCodeException error) {
      throw httpError(error);
    } catch (Exception error) {
      throw new DemandDingTalkException(-1, "钉钉请求失败：" + error.getClass().getSimpleName(), true);
    }
  }

  private DemandDingTalkException httpError(HttpStatusCodeException error) {
    int status = error.getRawStatusCode();
    String body = error.getResponseBodyAsString();
    String code = "";
    String message = body;
    try {
      Map<?, ?> parsed = mapper.readValue(body, Map.class);
      code = str(parsed.get("code"));
      message = str(parsed.get("message"));
      if (message.isEmpty()) message = str(parsed.get("errmsg"));
    } catch (Exception ignored) {
      if (message.length() > 300) message = message.substring(0, 300);
    }
    int numeric = parseCode(code);
    boolean transientError = status >= 500 || status == 429 || numeric == 401;
    if (numeric == 40014 || numeric == 42001 || numeric == 90018) transientError = true;
    return new DemandDingTalkException(numeric == 0 ? status : numeric, message, transientError);
  }

  private static int parseCode(String value) {
    try {
      return Integer.parseInt(value);
    } catch (Exception error) {
      return 0;
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> maps(Object value) {
    if (!(value instanceof List)) return Collections.emptyList();
    List<Map<String, Object>> result = new ArrayList<>();
    for (Object item : (List<?>) value)
      if (item instanceof Map) result.add(new LinkedHashMap<>((Map<String, Object>) item));
    return result;
  }

  private static Number number(Object value) {
    if (value instanceof Number) return (Number) value;
    try {
      return value == null ? null : Double.valueOf(value.toString());
    } catch (Exception error) {
      return null;
    }
  }

  private static String str(Object value) {
    return value == null ? "" : value.toString();
  }

  private static String clip(String value, int max) {
    String text = value == null ? "" : value.trim();
    return text.length() <= max ? text : text.substring(0, max);
  }

  private static boolean isTransientCode(int code) {
    return code == -1 || code == 40014 || code == 42001 || code == 90002 || code == 90018
        || code == 143103 || code == 143104 || code == 1430003 || code >= 500;
  }
}
