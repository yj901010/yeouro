package ai.yeouro.v2;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class ProductionProfileBoundaryTest {
  @Test
  void postgresProfilesDisableLegacyAnonymousHttpContract() {
    for (String profile : new String[] {"v2", "prod"}) {
      Properties settings = profile(profile);
      assertEquals("false", settings.getProperty("yeouro.v1.enabled"), profile);
      assertEquals("true", settings.getProperty("yeouro.v2.enabled"), profile);
      assertEquals("classpath:db/migration,classpath:db/v2",
          settings.getProperty("spring.flyway.locations"), profile);
    }
  }

  @Test
  void productionDoesNotEnableSimulationAndKeepsAuthExternallyConfigured() {
    Properties settings = profile("prod");
    assertEquals("false", settings.getProperty("yeouro.v2.demo-enabled"));
    assertEquals("${PHONE_JWT_ISSUER:}", settings.getProperty("yeouro.v2.auth.issuer"));
    assertEquals("${PHONE_JWT_JWKS_URI:}", settings.getProperty("yeouro.v2.auth.jwk-set-uri"));
  }

  private Properties profile(String name) {
    var yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ClassPathResource("application-" + name + ".yaml"));
    return yaml.getObject();
  }
}
