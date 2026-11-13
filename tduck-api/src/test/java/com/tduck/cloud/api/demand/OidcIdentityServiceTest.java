package com.tduck.cloud.api.demand;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import com.tduck.cloud.account.entity.UserEntity;
import com.tduck.cloud.account.service.UserService;
import com.tduck.cloud.account.vo.LoginUserVO;
import com.tduck.cloud.common.exception.BaseException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** 使用本机合成身份服务验证签名、应用绑定与单次登录，不访问真实账户。 */
class OidcIdentityServiceTest {
  HttpServer server;
  RSAKey key;
  OidcProperties p;
  OidcIdentityService service;
  JdbcTemplate jdbc;
  Map<String, Object> discovery;
  String currentNonce = "nonce";
  String currentSubject = "stable-subject";

  @BeforeEach
  void setup() throws Exception {
    key = new RSAKeyGenerator(2048).keyID("qa").generate();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String issuer = "http://127.0.0.1:" + server.getAddress().getPort();
    p = new OidcProperties();
    p.setEnabled(true);
    p.setIssuer(issuer);
    p.setClientId("qa-client");
    p.setClientSecret("synthetic-only");
    p.setRedirectUri("http://localhost/callback");
    p.setFrontendBase("http://localhost");
    discovery = new HashMap<>();
    discovery.put("issuer", issuer);
    discovery.put("jwks_uri", issuer + "/jwks");
    discovery.put("authorization_endpoint", issuer + "/authorize");
    discovery.put("token_endpoint", issuer + "/token");
    server.createContext("/jwks", e -> respond(e, new JWKSet(key.toPublicJWK()).toString()));
    server.createContext(
        "/.well-known/openid-configuration",
        e -> respond(e, new ObjectMapper().writeValueAsString(discovery)));
    server.createContext(
        "/token",
        e -> {
          try {
            respond(
                e,
                new ObjectMapper()
                    .writeValueAsString(
                        Collections.singletonMap(
                            "id_token",
                            signed("qa-client", p.getIssuer(), currentNonce, 60000, false))));
          } catch (Exception error) {
            e.sendResponseHeaders(500, -1);
            e.close();
          }
        });
    server.start();
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
    jdbc = new JdbcTemplate(ds);
    ScriptUtils.executeSqlScript(
        ds.getConnection(), new FileSystemResource("../doc/ruima-demand-schema.sql"));
    UserService users = mock(UserService.class);
    java.util.concurrent.atomic.AtomicLong nextUserId = new java.util.concurrent.atomic.AtomicLong(99);
    when(users.save(any(UserEntity.class)))
        .thenAnswer(
            i -> {
              ((UserEntity) i.getArgument(0)).setId(nextUserId.getAndIncrement());
              return true;
            });
    UserEntity user = new UserEntity();
    user.setId(99L);
    user.setDeleted(false);
    when(users.getById(99L)).thenReturn(user);
    when(users.getLoginResult(any(), any(), anyString()))
        .thenReturn(new LoginUserVO(null, "合成员工", "synthetic-session", false));
    DemandProperties flags = new DemandProperties();
    flags.setEnabled(true);
    DemandStore store = new DemandStore(jdbc, new ObjectMapper());
    service = new OidcIdentityService(p, new DemandAccess(store, flags, users), store, users);
  }

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
  }

  private void respond(com.sun.net.httpserver.HttpExchange e, String value)
      throws java.io.IOException {
    byte[] data = value.getBytes(StandardCharsets.UTF_8);
    e.getResponseHeaders().set("Content-Type", "application/json");
    e.sendResponseHeaders(200, data.length);
    e.getResponseBody().write(data);
    e.close();
  }

  private String signed(
      String audience, String issuer, String nonce, long duration, boolean forbidden)
      throws Exception {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(currentSubject)
            .audience(audience)
            .issueTime(new Date())
            .expirationTime(new Date(System.currentTimeMillis() + duration))
            .claim("owner", "ruima")
            .claim("nonce", nonce)
            .claim("isForbidden", forbidden)
            .build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  private Map<String, String> query(String url) throws Exception {
    Map<String, String> result = new HashMap<>();
    for (String part : new URI(url).getRawQuery().split("&")) {
      String[] p = part.split("=", 2);
      result.put(URLDecoder.decode(p[0], "UTF-8"), URLDecoder.decode(p[1], "UTF-8"));
    }
    return result;
  }

  @Test
  void verifiesSignatureAudienceIssuerNonceAndDisabledState() throws Exception {
    assertEquals(
        "stable-subject",
        service
            .verify(signed("qa-client", p.getIssuer(), "nonce", 60000, false), "nonce", discovery)
            .getSubject());
    assertThrows(
        BaseException.class,
        () ->
            service.verify(
                signed("other-app", p.getIssuer(), "nonce", 60000, false), "nonce", discovery));
    assertThrows(
        BaseException.class,
        () ->
            service.verify(
                signed("qa-client", "http://other-issuer", "nonce", 60000, false),
                "nonce",
                discovery));
    assertThrows(
        BaseException.class,
        () ->
            service.verify(
                signed("qa-client", p.getIssuer(), "other", 60000, false), "nonce", discovery));
    assertThrows(
        BaseException.class,
        () ->
            service.verify(
                signed("qa-client", p.getIssuer(), "nonce", -60000, false), "nonce", discovery));
    assertThrows(
        BaseException.class,
        () ->
            service.verify(
                signed("qa-client", p.getIssuer(), "nonce", 60000, true), "nonce", discovery));
    assertThrows(
        BaseException.class,
        () ->
            service.verify(
                new PlainJWT(new JWTClaimsSet.Builder().subject("forged").build()).serialize(),
                "nonce",
                discovery));
  }

  @Test
  void stateAndTicketAreBrowserBoundAndSingleUse() throws Exception {
    String browser = DemandStore.token();
    Map<String, String> q = query(service.start("/demand/mine", browser));
    currentNonce = q.get("nonce");
    assertEquals("S256", q.get("code_challenge_method"));
    assertFalse(q.get("code_challenge").isEmpty());
    assertThrows(
        BaseException.class, () -> service.callback(q.get("state"), "code", "other-browser"));
    Map<String, String> result = service.callback(q.get("state"), "code", browser);
    assertEquals("/demand/mine", result.get("returnPath"));
    assertThrows(BaseException.class, () -> service.callback(q.get("state"), "code", browser));
    assertThrows(
        BaseException.class,
        () -> service.exchange(result.get("ticket"), "other-browser", "127.0.0.1"));
    assertEquals(
        "synthetic-session",
        service.exchange(result.get("ticket"), browser, "127.0.0.1").getToken());
    assertThrows(
        BaseException.class, () -> service.exchange(result.get("ticket"), browser, "127.0.0.1"));
  }

  @Test
  void returnPathCannotRedirectOffsite() {
    for (String unsafe :
        Arrays.asList(
            "https://example.com",
            "//example.com",
            "/\\example.com",
            "/%2fexample.com",
            "/path\r\nLocation:other"))
      assertEquals("/demand", OidcIdentityService.safeReturn(unsafe));
    assertEquals("/s/test?x=1", OidcIdentityService.safeReturn("/s/test?x=1"));
  }
  @Test
  void differentlyCasedSubjectsRemainSeparateIdentities() throws Exception {
    String browser = DemandStore.token();
    currentSubject = "CaseUser";
    Map<String,String> first = query(service.start("/demand/mine", browser));
    currentNonce = first.get("nonce");
    service.callback(first.get("state"), "code", browser);
    currentSubject = "caseuser";
    Map<String,String> second = query(service.start("/demand/mine", browser));
    currentNonce = second.get("nonce");
    service.callback(second.get("state"), "code", browser);
    assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM rm_demand_identity", Long.class));
  }

}
