package com.tduck.cloud.api.demand;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tduck.cloud.common.exception.BaseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 钉钉应用配置的受控运行时覆盖。
 *
 * <p>appKey/appSecret/agentId 由管理员在「钉钉配置」页维护；只写入服务器上的
 * 0600 JSON 文件，不写数据库、Git 或日志。启动时读取并覆盖外部 secrets 中的默认值，
 * 保存后立即更新内存配置并清空 token 缓存，无需重启。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemandDingTalkConfig {
  private final DemandDingTalkProperties properties;
  private final DemandDingTalkClient client;
  private final ObjectMapper mapper;

  @PostConstruct
  public void load() {
    Path file = file();
    if (file == null || !Files.isRegularFile(file)) return;
    try {
      Map<String, Object> values =
          mapper.readValue(file.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
      apply(values, false);
      log.info("已加载钉钉运行时配置 {}", file);
    } catch (Exception error) {
      log.warn("钉钉运行时配置读取失败 {}", file, error);
    }
  }

  /** 管理员保存应用配置；空白 appSecret 表示保持原值。 */
  public synchronized Map<String, Object> save(Map<String, Object> body, Long operator) {
    Map<String, Object> values = body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
    String appKey = text(values.get("appKey"));
    String appSecret = text(values.get("appSecret"));
    Long agentId = number(values.get("agentId"));
    String linkBase = text(values.get("linkBase"));
    if (!appKey.isEmpty()) properties.setAppKey(appKey);
    if (!appSecret.isEmpty()) properties.setAppSecret(appSecret);
    if (agentId != null && agentId > 0) properties.setAgentId(agentId);
    if (!linkBase.isEmpty()) properties.setLinkBase(trimSlash(linkBase));
    if (values.containsKey("enabled"))
      properties.setEnabled(Boolean.parseBoolean(String.valueOf(values.get("enabled"))));
    if (values.containsKey("todoEnabled"))
      properties.setTodoEnabled(Boolean.parseBoolean(String.valueOf(values.get("todoEnabled"))));
    if (properties.isEnabled()) {
      if (blank(properties.getAppKey()) || blank(properties.getAppSecret())
          || properties.getAgentId() <= 0)
        throw new BaseException("启用钉钉推送前请填写 AppKey、AppSecret 和 AgentId", 400);
      if (blank(properties.getLinkBase()))
        throw new BaseException("启用钉钉推送前请填写链接基址", 400);
    }
    persist();
    client.invalidateTokens();
    log.info("钉钉应用配置已更新 operator={}", operator);
    return view();
  }

  /** 设置待办创建者；只能由已确认映射调用。 */
  public synchronized void setCreator(long userId, String unionId) {
    properties.setCreatorUserId(userId);
    properties.setCreatorUnionId(unionId);
    persist();
    log.info("钉钉待办创建者已更新 userId={}", userId);
  }

  public Map<String, Object> view() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("enabled", properties.isEnabled());
    result.put("appKey", properties.getAppKey());
    result.put("secretConfigured", !blank(properties.getAppSecret()));
    result.put("agentId", properties.getAgentId());
    result.put("linkBase", properties.getLinkBase());
    result.put("todoEnabled", properties.isTodoEnabled());
    result.put("creatorUserId", properties.getCreatorUserId());
    result.put("creatorUnionId", properties.getCreatorUnionId());
    return result;
  }

  private void apply(Map<String, Object> values, boolean persist) {
    String appKey = text(values.get("appKey"));
    String appSecret = text(values.get("appSecret"));
    Long agentId = number(values.get("agentId"));
    String linkBase = text(values.get("linkBase"));
    if (!appKey.isEmpty()) properties.setAppKey(appKey);
    if (!appSecret.isEmpty()) properties.setAppSecret(appSecret);
    if (agentId != null && agentId > 0) properties.setAgentId(agentId);
    if (!linkBase.isEmpty()) properties.setLinkBase(trimSlash(linkBase));
    if (values.containsKey("enabled"))
      properties.setEnabled(Boolean.parseBoolean(String.valueOf(values.get("enabled"))));
    if (values.containsKey("todoEnabled"))
      properties.setTodoEnabled(Boolean.parseBoolean(String.valueOf(values.get("todoEnabled"))));
    Long creatorUserId = number(values.get("creatorUserId"));
    if (creatorUserId != null && creatorUserId > 0) properties.setCreatorUserId(creatorUserId);
    String creatorUnionId = text(values.get("creatorUnionId"));
    if (!creatorUnionId.isEmpty()) properties.setCreatorUnionId(creatorUnionId);
  }

  private void persist() {
    Path file = file();
    if (file == null) throw new BaseException("未配置钉钉运行时配置文件路径", 500);
    try {
      Path parent = file.toAbsolutePath().getParent();
      if (parent != null) Files.createDirectories(parent);
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("enabled", properties.isEnabled());
      values.put("appKey", nullToEmpty(properties.getAppKey()));
      values.put("appSecret", nullToEmpty(properties.getAppSecret()));
      values.put("agentId", properties.getAgentId());
      values.put("linkBase", nullToEmpty(properties.getLinkBase()));
      values.put("todoEnabled", properties.isTodoEnabled());
      values.put("creatorUserId", properties.getCreatorUserId());
      values.put("creatorUnionId", nullToEmpty(properties.getCreatorUnionId()));
      byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(values);
      Path temp =
          Files.createTempFile(parent, "dingtalk-config-", ".tmp");
      Files.write(temp, bytes);
      permissions(temp);
      Files.move(
          temp,
          file,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
      permissions(file);
    } catch (IOException error) {
      throw new BaseException("钉钉配置保存失败，请检查运行时目录权限", 500);
    }
  }

  private Path file() {
    String path = properties.getRuntimeConfig();
    if (blank(path)) return null;
    return Paths.get(path.trim());
  }

  private void permissions(Path path) {
    try {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    } catch (Exception ignored) {
      /* Windows 等不支持 POSIX 权限的环境跳过。 */
    }
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static Long number(Object value) {
    if (value == null) return null;
    try {
      return Long.valueOf(String.valueOf(value).trim());
    } catch (NumberFormatException error) {
      return null;
    }
  }

  private static String trimSlash(String value) {
    String result = value == null ? "" : value.trim();
    while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
    return result;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static boolean blank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
