package auth.service;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import auth.cognito.CognitoGateway;
import auth.cognito.CognitoUser;
import auth.dto.UserDto;
import auth.entity.UserAccount;
import auth.exception.ConflictException;
import auth.exception.IdentityProviderException;
import auth.exception.UserNotFoundException;
import auth.repository.UserAccountRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The account model against H2: Cognito holds the login, one row holds role and profile under Cognito's sub.
 * Cognito and the event publisher are mocked so the test needs neither AWS nor RabbitMQ.
 */
@SpringBootTest
@ActiveProfiles("test")
class UserServiceIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @MockitoBean
    private CognitoGateway cognito;

    @MockitoBean
    private UserEventPublisher eventPublisher;

    /** The Cognito username of {@code sub} (a real pool addresses users by username, not by sub). */
    private static String name(UUID sub) {
        return "user-" + sub;
    }

    /** A user who signed up and verified their email in Cognito through the frontend, with no group yet. */
    private UUID signedUp(String email) {
        UUID sub = UUID.randomUUID();
        when(cognito.lookup(name(sub))).thenReturn(new CognitoUser(email, true, Set.of()));
        return sub;
    }

    private UserDto.Response onboard(UUID sub, String phone) {
        return userService.onboard(sub, name(sub), new UserDto.OnboardRequest("Pat", UserAccount.Role.PARTICIPANT, phone));
    }

    @Test
    void onboard_createsProfileWithCognitosSubAndEmail_addsGroup_andAnnouncesUserCreated() {
        UUID sub = signedUp("Merged@Example.com");

        UserDto.Response created = onboard(sub, "+65 9000 0000");

        assertThat(created.getId()).isEqualTo(sub);
        UserDto.Response profile = userService.getById(sub);
        assertThat(profile.getEmail()).isEqualTo("merged@example.com");
        assertThat(profile.getRole()).isEqualTo(UserAccount.Role.PARTICIPANT);
        assertThat(profile.getPhoneNumber()).isEqualTo("+65 9000 0000");
        verify(cognito).addUserToGroup(name(sub), UserAccount.Role.PARTICIPANT);
        verify(eventPublisher).publishUserCreated(any());
    }

    @Test
    void onboard_twice_isAConflict() {
        UUID sub = signedUp("twice@example.com");
        onboard(sub, null);

        assertThatThrownBy(() -> onboard(sub, null))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Your profile already exists");
    }

    @Test
    void onboard_whenCognitoFailsToAddTheGroup_leavesNoProfileBehind_soItCanBeRetried() {
        UUID sub = signedUp("retry@example.com");
        doThrow(new IdentityProviderException("Cognito group membership failed", null))
                .when(cognito).addUserToGroup(name(sub), UserAccount.Role.PARTICIPANT);

        assertThatThrownBy(() -> onboard(sub, null)).isInstanceOf(IdentityProviderException.class);

        assertThat(userAccountRepository.existsById(sub)).isFalse();
        verify(eventPublisher, never()).publishUserCreated(any());
    }

    @Test
    void update_changesOnlyProfileFields() {
        UUID sub = signedUp("update@example.com");
        onboard(sub, null);

        UserDto.Response updated = userService.update(sub, new UserDto.UpdateRequest("Patricia", "+65 8000 0000"));

        assertThat(updated.getFullName()).isEqualTo("Patricia");
        assertThat(updated.getPhoneNumber()).isEqualTo("+65 8000 0000");
        assertThat(updated.getRole()).isEqualTo(UserAccount.Role.PARTICIPANT);
        assertThat(updated.getEmail()).isEqualTo("update@example.com");
    }

    @Test
    void delete_removesCognitoUserFirst_thenProfile() {
        UUID sub = signedUp("delete@example.com");
        onboard(sub, null);

        userService.delete(sub, name(sub));

        assertThatThrownBy(() -> userService.getById(sub)).isInstanceOf(UserNotFoundException.class);
        InOrder order = inOrder(cognito, eventPublisher);
        order.verify(cognito).deleteUser(name(sub));
        order.verify(eventPublisher).publishUserDeleted(sub);
    }

    @Test
    void delete_keepsProfile_whenCognitoFails() {
        UUID sub = signedUp("keep@example.com");
        onboard(sub, null);
        doThrow(new IdentityProviderException("Cognito account deletion failed", null)).when(cognito).deleteUser(name(sub));

        assertThatThrownBy(() -> userService.delete(sub, name(sub))).isInstanceOf(IdentityProviderException.class);

        assertThat(userService.getById(sub).getEmail()).isEqualTo("keep@example.com");
        verify(eventPublisher, never()).publishUserDeleted(any());
    }

    @Test
    void delete_unknownId_throwsNotFound_withoutCallingCognito() {
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> userService.delete(unknown, name(unknown))).isInstanceOf(UserNotFoundException.class);
        verify(cognito, never()).deleteUser(any());
    }
}
