package vaultWeb.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import vaultWeb.exceptions.DuplicateUsernameException;
import vaultWeb.exceptions.UnauthorizedException;
import vaultWeb.models.User;
import vaultWeb.repositories.UserRepository;
import vaultWeb.services.auth.PersonalAccessTokenService;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

  @Mock private UserRepository userRepository;

  @Mock private PasswordEncoder passwordEncoder;

  @Mock private PersonalAccessTokenService personalAccessTokenService;

  @InjectMocks private UserService userService;

  private User createUser(Long id, String username, String password) {
    User user = new User();
    user.setId(id);
    user.setUsername(username);
    user.setPassword(password);
    return user;
  }

  @Test
  void shouldRegisterUserSuccessfully() {
    User user = createUser(null, "newuser", "plainPassword");

    when(userRepository.existsByUsername("newuser")).thenReturn(false);
    when(passwordEncoder.encode("plainPassword")).thenReturn("hashedPassword");

    userService.registerUser(user);

    assertEquals("hashedPassword", user.getPassword());
    verify(userRepository).save(user);
  }

  @Test
  void shouldFailRegister_WhenUsernameExists() {
    User user = createUser(null, "existing", "password");

    when(userRepository.existsByUsername("existing")).thenReturn(true);

    assertThrows(DuplicateUsernameException.class, () -> userService.registerUser(user));

    verify(userRepository, never()).save(any());
  }

  @Test
  void shouldReturnTrue_WhenUsernameExists() {
    when(userRepository.existsByUsername("test")).thenReturn(true);

    assertTrue(userService.usernameExists("test"));
  }

  @Test
  void shouldReturnFalse_WhenUsernameDoesNotExist() {
    when(userRepository.existsByUsername("test")).thenReturn(false);

    assertFalse(userService.usernameExists("test"));
  }

  @Test
  void shouldReturnAllUsers() {
    List<User> users = List.of(createUser(1L, "user1", "pwd1"), createUser(2L, "user2", "pwd2"));

    when(userRepository.findAll()).thenReturn(users);

    List<User> result = userService.getAllUsers();

    assertEquals(2, result.size());
    verify(userRepository).findAll();
  }

  @Test
  void shouldChangePasswordSuccessfully() {
    User user = createUser(1L, "test", "oldHashed");

    when(passwordEncoder.matches("old", "oldHashed")).thenReturn(true);
    when(passwordEncoder.encode("new")).thenReturn("newHashed");

    userService.changePassword(user, "old", "new");

    assertEquals("newHashed", user.getPassword());
    verify(userRepository).save(user);
  }

  @Test
  void shouldFailChangePassword_WhenOldPasswordIncorrect() {
    User user = createUser(1L, "test", "oldHashed");

    when(passwordEncoder.matches("wrong", "oldHashed")).thenReturn(false);

    assertThrows(
        UnauthorizedException.class, () -> userService.changePassword(user, "wrong", "new"));

    verify(userRepository, never()).save(any());
  }
}
