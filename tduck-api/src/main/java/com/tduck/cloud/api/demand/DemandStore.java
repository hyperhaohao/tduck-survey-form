package com.tduck.cloud.api.demand;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tduck.cloud.common.exception.BaseException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 参数化需求数据访问与无凭据日志的基础转换。 */
@Repository
@RequiredArgsConstructor
public class DemandStore {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private static final SecureRandom RANDOM = new SecureRandom();

  /** 查询列表，统一数据库列名并将身份标识序列化为字符串。 */
  public List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbc.query(
        sql,
        (rs, n) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
            String raw = rs.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT);
            StringBuilder key = new StringBuilder();
            boolean upper = false;
            for (char c : raw.toCharArray()) {
              if (c == '_') {
                upper = true;
              } else {
                key.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
              }
            }
            Object value = rs.getObject(i);
            if (value instanceof Number
                && (key.toString().endsWith("Id") || key.toString().equals("id")))
              value = value.toString();
            row.put(key.toString(), value);
          }
          return row;
        },
        args);
  }
  /** 获取一条可选记录。 */
  public Map<String, Object> one(String sql, Object... args) {
    List<Map<String, Object>> rows = rows(sql, args);
    return rows.isEmpty() ? null : rows.get(0);
  }
  /** 执行参数化更新。 */
  public int update(String sql, Object... args) {
    return jdbc.update(sql, args);
  }
  /** 批量执行参数化更新；空集合直接返回。 */
  public int[] batch(String sql, List<Object[]> rows) {
    if (rows == null || rows.isEmpty()) return new int[0];
    return jdbc.batchUpdate(sql, rows);
  }
  /** 统计记录数量。 */
  public long count(String sql, Object... args) {
    Long n = jdbc.queryForObject(sql, Long.class, args);
    return n == null ? 0 : n;
  }
  /** 解析数据库中的原表单数据。 */
  public Map<String, Object> json(Object raw) {
    try {
      return raw == null
          ? new LinkedHashMap<>()
          : mapper.readValue(raw.toString(), new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new BaseException("需求数据解析失败", e);
    }
  }
  /** 生成不可猜测的凭证，仅向领取者返回一次。 */
  public static String token() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
  /** 生成公开业务标识。 */
  public static String id() {
    return UUID.randomUUID().toString();
  }
  /** 保存查询码和一次性凭证的摘要。 */
  public static String hash(String text) {
    try {
      byte[] bytes =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      StringBuilder s = new StringBuilder();
      for (byte b : bytes) s.append(String.format("%02x", b));
      return s.toString();
    } catch (Exception e) {
      throw new IllegalStateException("摘要算法不可用", e);
    }
  }
  /** 比较凭证摘要，避免直接比较明文。 */
  public static boolean matches(String expected, String supplied) {
    return expected != null
        && supplied != null
        && MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            hash(supplied).getBytes(StandardCharsets.UTF_8));
  }
  /** 读取布尔数据库值。 */
  public static boolean flag(Object value) {
    return Boolean.TRUE.equals(value) || "1".equals(String.valueOf(value));
  }
  /** 读取可空用户标识。 */
  public static Long number(Object value) {
    return value == null ? null : Long.valueOf(value.toString());
  }
  /** 读取并约束必填文字。 */
  public static String text(Object value, int max) {
    String s = value == null ? "" : value.toString().trim();
    if (s.isEmpty() || s.length() > max) throw new BaseException("内容不能为空且不能超过" + max + "字");
    return s;
  }
}
