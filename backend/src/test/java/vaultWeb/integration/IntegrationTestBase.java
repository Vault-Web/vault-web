package vaultWeb.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import vaultWeb.repositories.RefreshTokenRepository;
import vaultWeb.repositories.SecurityEventRepository;
import vaultWeb.repositories.UserRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@AutoConfigureTestRestTemplate
abstract class IntegrationTestBase {
  @Autowired protected MockMvc mockMvc;
  @Autowired protected ObjectMapper objectMapper;
  @Autowired protected UserRepository userRepository;
  @Autowired protected RefreshTokenRepository refreshTokenRepository;
  @Autowired protected SecurityEventRepository securityEventRepository;
  @Autowired protected TestRestTemplate restTemplate;
  @LocalServerPort protected int port;

  @BeforeEach
  void setUp() {
    // Clear database before each test
    refreshTokenRepository.deleteAll();
    securityEventRepository.deleteAll();
    userRepository.deleteAll();
  }
}
