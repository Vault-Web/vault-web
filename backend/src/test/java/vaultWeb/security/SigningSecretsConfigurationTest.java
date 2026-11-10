package vaultWeb.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Loads the real {@code application.properties} (not the test one) to pin that it carries no
 * signing secret of its own and takes each one from its environment variable.
 */
class SigningSecretsConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer());

  @Test
  void withoutEnvironmentVariables_noSigningSecretIsConfigured() {
    runner.run(
        context -> {
          assertThat(context.getEnvironment().getProperty("jwt.secret")).isEmpty();
          assertThat(context.getEnvironment().getProperty("jwt.refreshSecret")).isEmpty();
          assertThat(context.getEnvironment().getProperty("jwt.mediaSecret")).isEmpty();
        });
  }

  @Test
  void eachSecretIsTakenFromItsEnvironmentVariable() {
    runner
        .withSystemProperties(
            "JWT_SECRET=value-of-jwt-secret",
            "JWT_REFRESHSECRET=value-of-refresh-secret",
            "JWT_MEDIASECRET=value-of-media-secret")
        .run(
            context -> {
              assertThat(context.getEnvironment().getProperty("jwt.secret"))
                  .isEqualTo("value-of-jwt-secret");
              assertThat(context.getEnvironment().getProperty("jwt.refreshSecret"))
                  .isEqualTo("value-of-refresh-secret");
              assertThat(context.getEnvironment().getProperty("jwt.mediaSecret"))
                  .isEqualTo("value-of-media-secret");
            });
  }
}
