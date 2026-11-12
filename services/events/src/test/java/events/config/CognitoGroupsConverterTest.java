package events.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Unit tests for {@link CognitoGroupsConverter}: turning the {@code cognito:groups} claim of an access token into
 * the {@code ROLE_} authorities that {@link SecurityConfig} and the controller's {@code @PreAuthorize} rules check.
 */
class CognitoGroupsConverterTest {

    private final CognitoGroupsConverter converter = new CognitoGroupsConverter();

    @Test
    void eachGroupBecomesAnUpperCaseRole() {
        assertThat(converter.convert(jwtWithGroups(List.of("participant", "Organiser", "ADMIN"))))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_PARTICIPANT", "ROLE_ORGANISER", "ROLE_ADMIN");
    }

    @Test
    void aTokenWithoutTheGroupsClaimHasNoRoles() {
        // Cognito leaves the claim out for a user who is in no group
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject("user-1").build();

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    void anEmptyGroupsClaimHasNoRoles() {
        assertThat(converter.convert(jwtWithGroups(List.of()))).isEmpty();
    }

    // Upper-casing with the server's default locale would turn "admin" into "ADMİN" on a Turkish system
    @Test
    void rolesDoNotDependOnTheServerLocale() {
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            assertThat(converter.convert(jwtWithGroups(List.of("admin", "participant"))))
                    .extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_ADMIN", "ROLE_PARTICIPANT");
        } finally {
            Locale.setDefault(original);
        }
    }

    private static Jwt jwtWithGroups(List<String> groups) {
        return Jwt.withTokenValue("token").header("alg", "RS256").subject("user-1")
                .claim("cognito:groups", groups)
                .build();
    }
}
