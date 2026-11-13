package com.tduck.cloud.api.demand;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 统一登录应用配置；密钥仅由环境或忽略的本地文件注入。 */
@Data
@Component
@ConfigurationProperties(prefix = "ruima.oidc")
public class OidcProperties {
  private boolean enabled;
  private String issuer;
  private String authorizationEndpoint;
  private String clientId;
  private String clientSecret;
  private String redirectUri;
  private String frontendBase;
  private String organization = "ruima";
}
