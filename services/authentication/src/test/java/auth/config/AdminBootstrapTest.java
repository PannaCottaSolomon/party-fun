package auth.config;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import auth.cognito.CognitoGateway;
import auth.entity.UserAccount;
import auth.exception.IdentityProviderException;
import auth.repository.UserAccountRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Startup seeding of the Cognito groups and the single ADMIN account. */
class AdminBootstrapTest {

    private final UserAccountRepository repository = mock(UserAccountRepository.class);
    private final CognitoGateway cognito = mock(CognitoGateway.class);

    private AdminBootstrap bootstrap(String password) {
        return new AdminBootstrap(repository, cognito, " Admin@Example.com ", "", password, "Platform Admin");
    }

    @Test
    void seedsGroupsAndAdmin_withCognitosSubAsId() {
        UUID sub = UUID.randomUUID();
        when(cognito.isConfigured()).thenReturn(true);
        // No username configured: the email is the Cognito username.
        when(cognito.ensureUser("admin@example.com", "admin@example.com", "Example-Pass1!", UserAccount.Role.ADMIN))
                .thenReturn(sub);

        bootstrap("Example-Pass1!").run(null);

        verify(cognito).ensureGroups();
        ArgumentCaptor<UserAccount> saved = ArgumentCaptor.forClass(UserAccount.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(sub);
        assertThat(saved.getValue().getEmail()).isEqualTo("admin@example.com");
        assertThat(saved.getValue().getRole()).isEqualTo(UserAccount.Role.ADMIN);
    }

    @Test
    void configuredUsername_isUsedForCognito_forAPoolThatSignsInWithUsernames() {
        UUID sub = UUID.randomUUID();
        when(cognito.isConfigured()).thenReturn(true);
        when(cognito.ensureUser("admin", "admin@example.com", "Example-Pass1!", UserAccount.Role.ADMIN)).thenReturn(sub);

        new AdminBootstrap(repository, cognito, "admin@example.com", " Admin ", "Example-Pass1!", "Platform Admin")
                .run(null);

        verify(cognito).ensureUser("admin", "admin@example.com", "Example-Pass1!", UserAccount.Role.ADMIN);
        verify(repository).save(any());
    }

    @Test
    void existingAdminProfile_isLeftAlone() {
        when(cognito.isConfigured()).thenReturn(true);
        when(repository.existsByEmail("admin@example.com")).thenReturn(true);

        bootstrap("Example-Pass1!").run(null);

        verify(cognito).ensureGroups();
        verify(cognito, never()).ensureUser(any(), any(), any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void blankPassword_createsGroupsButNoAdmin() {
        when(cognito.isConfigured()).thenReturn(true);

        bootstrap("").run(null);

        verify(cognito).ensureGroups();
        verify(cognito, never()).ensureUser(any(), any(), any(), any());
    }

    @Test
    void cognitoNotConfigured_skipsEverything() {
        bootstrap("Example-Pass1!").run(null);

        verify(cognito, never()).ensureGroups();
        verifyNoInteractions(repository);
    }

    @Test
    void cognitoFailure_isLogged_andDoesNotStopStartup() {
        when(cognito.isConfigured()).thenReturn(true);
        doThrow(new IdentityProviderException("Cognito group lookup failed", null)).when(cognito).ensureGroups();

        bootstrap("Example-Pass1!").run(null); // does not throw

        verify(repository, never()).save(any());
    }
}
