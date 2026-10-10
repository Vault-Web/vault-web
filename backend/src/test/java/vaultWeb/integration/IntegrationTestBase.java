package vaultWeb.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import vaultWeb.repositories.RefreshTokenRepository;
import vaultWeb.repositories.SecurityEventRepository;
import vaultWeb.repositories.NotificationPreferenceRepository;
import vaultWeb.repositories.NotificationRepository;
import vaultWeb.repositories.UserRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureMockMvc
abstract class IntegrationTestBase {
  @Autowired protected MockMvc mockMvc;
  @Autowired protected ObjectMapper objectMapper;
  @Autowired protected UserRepository userRepository;
  @Autowired protected RefreshTokenRepository refreshTokenRepository;
  @Autowired protected SecurityEventRepository securityEventRepository;
  @Autowired protected NotificationRepository notificationRepository;
  @Autowired protected NotificationPreferenceRepository notificationPreferenceRepository;
  @Autowired protected TestRestTemplate restTemplate;
  @LocalServerPort protected int port;

  @BeforeEach
  void setUp() {
    // Clear database before each test
    refreshTokenRepository.deleteAll();
    notificationRepository.deleteAll();
    notificationPreferenceRepository.deleteAll();
    securityEventRepository.deleteAll();
    userRepository.deleteAll();
  }
}
