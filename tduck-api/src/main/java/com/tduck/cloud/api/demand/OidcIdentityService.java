package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.*;
import com.nimbusds.jwt.*;
import com.nimbusds.jwt.proc.*;
import com.tduck.cloud.account.entity.UserEntity;
import com.tduck.cloud.account.entity.enums.AccountChannelEnum;
import com.tduck.cloud.account.service.UserService;
import com.tduck.cloud.account.vo.LoginUserVO;
import com.tduck.cloud.common.exception.BaseException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** 单次授权码登录；校验签名、签发者、受众、有效期、nonce、组织及浏览器绑定。 */
@Service
@RequiredArgsConstructor
public class OidcIdentityService {
  private final OidcProperties properties;
  private final DemandAccess access;
  private final DemandStore store;
  private final UserService users;
  /** 建立短时浏览器绑定的授权状态及 PKCE。 */
  @Transactional
  public String start(String returnPath, String browser) {
    ready();
    returnPath = safeReturn(returnPath);
    Map<String, Object> discovery = discovery();
    String state = token(), nonce = token(), verifier = token();
    String challenge =
        Base64.getUrlEncoder().withoutPadding().encodeToString(hexBytes(hash(verifier)));
    store.update("DELETE FROM rm_demand_login_state WHERE expires_at<CURRENT_TIMESTAMP");
    store.update("DELETE FROM rm_demand_login_ticket WHERE expires_at<CURRENT_TIMESTAMP");
    store.update(
        "INSERT INTO rm_demand_login_state(state_hash,browser_hash,nonce,verifier,return_path,expires_at) VALUES(?,?,?,?,?,?)",
        hash(state),
        hash(browser),
        nonce,
        verifier,
        returnPath,
        new java.sql.Timestamp(System.currentTimeMillis() + 600000));
    String auth =
        properties.getAuthorizationEndpoint() == null
                || properties.getAuthorizationEndpoint().isEmpty()
            ? discovery.get("authorization_endpoint").toString()
            : properties.getAuthorizationEndpoint();
    return UriComponentsBuilder.fromHttpUrl(auth)
        .queryParam("client_id", properties.getClientId())
        .queryParam("response_type", "code")
        .queryParam("redirect_uri", properties.getRedirectUri())
        .queryParam("scope", "openid profile email")
        .queryParam("state", state)
        .queryParam("nonce", nonce)
        .queryParam("code_challenge", challenge)
        .queryParam("code_challenge_method", "S256")
        .build()
        .encode()
        .toUriString();
  }
  /** 消耗授权状态后验证令牌，仅返回一次性交换票据。 */
  @Transactional
  public Map<String, String> callback(String state, String code, String browser) {
    ready();
    Map<String, Object> row =
        store.one("SELECT * FROM rm_demand_login_state WHERE state_hash=? FOR UPDATE", hash(state));
    if (row == null
        || browser == null
        || !matches(row.get("browserHash").toString(), browser)
        || ((java.util.Date) row.get("expiresAt")).getTime() < System.currentTimeMillis())
      throw new BaseException("登录请求已过期，请重新登录");
    store.update("DELETE FROM rm_demand_login_state WHERE state_hash=?", hash(state));
    Map<String, Object> config = discovery();
    MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
    body.add("grant_type", "authorization_code");
    body.add("client_id", properties.getClientId());
    body.add("client_secret", properties.getClientSecret());
    body.add("redirect_uri", properties.getRedirectUri());
    body.add("code", text(code, 4096));
    body.add("code_verifier", row.get("verifier").toString());
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    Map<?, ?> tokens;
    try {
      tokens =
          http()
              .postForObject(
                  config.get("token_endpoint").toString(),
                  new HttpEntity<>(body, headers),
                  Map.class);
    } catch (Exception e) {
      throw new BaseException("登录验证失败，请重新登录");
    }
    if (tokens == null || tokens.get("id_token") == null) throw new BaseException("登录服务未返回身份凭证");
    JWTClaimsSet claims =
        verify(tokens.get("id_token").toString(), row.get("nonce").toString(), config);
    Long userId = identity(claims);
    String ticket = token();
    store.update(
        "INSERT INTO rm_demand_login_ticket(ticket_hash,user_id,browser_hash,expires_at) VALUES(?,?,?,?)",
        hash(ticket),
        userId,
        hash(browser),
        new java.sql.Timestamp(System.currentTimeMillis() + 60000));
    Map<String, String> result = new LinkedHashMap<>();
    result.put("ticket", ticket);
    result.put("returnPath", row.get("returnPath").toString());
    return result;
  }
  /** 将短时票据兑换为原系统会话，令牌不放入重定向地址。 */
  @Transactional
  public LoginUserVO exchange(String ticket, String browser, String ip) {
    ready();
    Map<String, Object> row =
        store.one(
            "SELECT * FROM rm_demand_login_ticket WHERE ticket_hash=? FOR UPDATE", hash(ticket));
    if (row == null
        || browser == null
        || !matches(row.get("browserHash").toString(), browser)
        || ((java.util.Date) row.get("expiresAt")).getTime() < System.currentTimeMillis())
      throw new BaseException("登录凭证已过期，请重新登录");
    store.update("DELETE FROM rm_demand_login_ticket WHERE ticket_hash=?", hash(ticket));
    UserEntity user = users.getById(number(row.get("userId")));
    if (user == null || Boolean.TRUE.equals(user.getDeleted()))
      throw new BaseException("账户已停用", 403);
    return users.getLoginResult(user, AccountChannelEnum.OIDC, ip);
  }
  /** 验证 JWT 与指定应用绑定，不信任邮箱、姓名或管理员声明作为账户授权依据。 */
  public JWTClaimsSet verify(String jwt, String nonce, Map<String, Object> config) {
    try {
      String jwks = http().getForObject(config.get("jwks_uri").toString(), String.class);
      DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
      Set<JWSAlgorithm> algorithms =
          new HashSet<>(
              Arrays.asList(
                  JWSAlgorithm.RS256,
                  JWSAlgorithm.RS512,
                  JWSAlgorithm.ES256,
                  JWSAlgorithm.ES384,
                  JWSAlgorithm.ES512));
      processor.setJWSKeySelector(
          new JWSVerificationKeySelector<>(
              algorithms, new ImmutableJWKSet<SecurityContext>(JWKSet.parse(jwks))));
      JWTClaimsSet c = processor.process(jwt, null);
      if (!properties.getIssuer().equals(c.getIssuer())
          || c.getSubject() == null
          || c.getSubject().isEmpty()
          || !c.getAudience().contains(properties.getClientId())
          || c.getExpirationTime() == null
          || c.getExpirationTime().getTime() <= System.currentTimeMillis()
          || !nonce.equals(c.getStringClaim("nonce"))) throw new BaseException("身份凭证校验失败");
      if (c.getNotBeforeTime() != null
          && c.getNotBeforeTime().getTime() > System.currentTimeMillis() + 30000)
        throw new BaseException("身份凭证尚未生效");
      if (c.getIssueTime() == null
          || c.getIssueTime().getTime() > System.currentTimeMillis() + 30000)
        throw new BaseException("身份凭证签发时间无效");
      if (c.getAudience().size() > 1 && !properties.getClientId().equals(c.getStringClaim("azp")))
        throw new BaseException("身份凭证应用不匹配");
      if (!properties.getOrganization().equals(c.getStringClaim("owner")))
        throw new BaseException("账户不属于允许的组织", 403);
      if (Boolean.TRUE.equals(c.getBooleanClaim("isForbidden"))
          || Boolean.TRUE.equals(c.getBooleanClaim("isDeleted")))
        throw new BaseException("账户已停用", 403);
      return c;
    } catch (BaseException e) {
      throw e;
    } catch (Exception e) {
      throw new BaseException("身份凭证校验失败，请重新登录");
    }
  }
  /** 按 issuer 和 subject 唯一关联，首次登录仅创建普通本地账户。 */
  private Long identity(JWTClaimsSet claims) {
    String subject = claims.getSubject();
    if (subject.length() > 255) throw new BaseException("身份标识过长");
    Map<String, Object> binding =
        store.one(
            "SELECT user_id FROM rm_demand_identity WHERE identity_hash=?",
            hash(properties.getIssuer() + "\n" + subject));
    if (binding != null) return number(binding.get("userId"));
    // 锁定配置行使并发首次登录不会创建重复映射；本模块部署要求迁移已执行。
    store.one("SELECT user_id FROM rm_demand_admin WHERE user_id=0 FOR UPDATE");
    binding =
        store.one(
            "SELECT user_id FROM rm_demand_identity WHERE identity_hash=?",
            hash(properties.getIssuer() + "\n" + subject));
    if (binding != null) return number(binding.get("userId"));
    UserEntity user = new UserEntity();
    Object display = claims.getClaim("displayName");
    if (display == null) display = claims.getClaim("name");
    String name = display == null ? "员工" : display.toString();
    user.setName(name.substring(0, Math.min(32, name.length())));
    user.setPassword(hash(token()));
    user.setPasswordType(0);
    user.setDeleted(false);
    user.setRegChannel(AccountChannelEnum.OIDC);
    user.setNeedChangePassword(false);
    users.save(user);
    store.update(
        "INSERT INTO rm_demand_identity(identity_hash,issuer,subject,user_id) VALUES(?,?,?,?)",
        hash(properties.getIssuer() + "\n" + subject),
        properties.getIssuer(),
        subject,
        user.getId());
    return user.getId();
  }
  /** 发现文档的 issuer 必须与实际 JWT 签发者一致。 */
  private Map<String, Object> discovery() {
    try {
      Map<String, Object> config =
          http()
              .getForObject(
                  properties.getIssuer() + "/.well-known/openid-configuration", Map.class);
      if (config == null || !properties.getIssuer().equals(config.get("issuer")))
        throw new BaseException("登录服务签发地址不匹配");
      return config;
    } catch (BaseException e) {
      throw e;
    } catch (Exception e) {
      throw new BaseException("登录服务暂时不可用，请稍后重试");
    }
  }
  /** 登录 HTTP 请求设置连接及读取超时。 */
  private RestTemplate http() {
    SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
    f.setConnectTimeout(5000);
    f.setReadTimeout(10000);
    return new RestTemplate(f);
  }
  /** 尚未配置时给出可理解的提示，不回退到共享应用。 */
  private void ready() {
    access.requireEnabled();
    if (!properties.isEnabled()
        || blank(properties.getIssuer())
        || blank(properties.getClientId())
        || blank(properties.getClientSecret())
        || blank(properties.getRedirectUri())
        || blank(properties.getFrontendBase())) throw new BaseException("登录尚未配置，请联系管理员");
  }
  /** 限制返回地址只能是本站路径，禁止开放重定向。 */
  public static String safeReturn(String path) {
    if (path == null
        || !path.startsWith("/")
        || path.startsWith("//")
        || path.contains("\\")
        || path.contains("\r")
        || path.contains("\n")
        || path.toLowerCase(Locale.ROOT).contains("%2f")
        || path.toLowerCase(Locale.ROOT).contains("%5c")) return "/demand";
    return path;
  }
  /** 解析 SHA256 十六进制摘要以生成 PKCE S256。 */
  private byte[] hexBytes(String hex) {
    byte[] result = new byte[hex.length() / 2];
    for (int i = 0; i < result.length; i++)
      result[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
    return result;
  }
  /** 判定必要配置是否缺失。 */
  private boolean blank(String s) {
    return s == null || s.trim().isEmpty();
  }
}
