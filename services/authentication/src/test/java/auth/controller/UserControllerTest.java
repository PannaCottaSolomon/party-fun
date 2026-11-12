package auth.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import auth.dto.UserDto;
import auth.entity.UserAccount;
import auth.exception.UserNotFoundException;
import auth.security.CognitoTokenVerifier;
import auth.security.TestTokens;
import auth.service.UserService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for the /user profile endpoints, including their token protection. Tokens are Cognito-shaped
 * access tokens signed with a test key ({@link TestTokens}) and checked by the real {@link CognitoTokenVerifier}
 * and production validators, so these exercise genuine signature verification, not a stub.
 */
@WebMvcTest(UserController.class)
@Import({CognitoTokenVerifier.class, TestTokens.DecoderConfig.class})
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    private final UUID aliceId = UUID.randomUUID();
    private final UUID bobId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    private String bearer(UUID id, UserAccount.Role role) {
        return "Bearer " + TestTokens.access(id, role.groupName());
    }

    private String alice() { return bearer(aliceId, UserAccount.Role.PARTICIPANT); }
    private String bob() { return bearer(bobId, UserAccount.Role.ORGANISER); }
    private String admin() { return bearer(adminId, UserAccount.Role.ADMIN); }

    private UserDto.Response sample(UUID id) {
        Instant now = Instant.now();
        return new UserDto.Response(id, "alice@example.com", "Alice", UserAccount.Role.PARTICIPANT,
                "+65 1234 5678", now, now);
    }

    // ---- authentication (401) ----

    @Test
    void noToken_returns401() throws Exception {
        mockMvc.perform(get("/user/{id}", aliceId)).andExpect(status().isUnauthorized());
        verify(userService, never()).getById(any());
    }

    @Test
    void malformedOrForgedToken_returns401() throws Exception {
        mockMvc.perform(get("/user/{id}", aliceId).header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        // A real token with its payload tampered (role flipped) fails the signature check.
        String[] parts = alice().substring("Bearer ".length()).split("\\.");
        String tampered = parts[0] + "." + parts[1] + "x." + parts[2];
        mockMvc.perform(get("/user/{id}", aliceId).header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedByAnotherKey_returns401() throws Exception {
        mockMvc.perform(get("/user").header("Authorization",
                        "Bearer " + TestTokens.signedWithOtherKey(adminId, "admin")))
                .andExpect(status().isUnauthorized());
        verify(userService, never()).getAll();
    }

    @Test
    void userWithoutAdminGroup_cannotListUsers_evenIfOtherGroupsPresent() throws Exception {
        mockMvc.perform(get("/user").header("Authorization",
                        "Bearer " + TestTokens.access(aliceId, "participant", "organiser", "beta-testers")))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonBearerScheme_returns401() throws Exception {
        mockMvc.perform(get("/user/{id}", aliceId).header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized());
    }

    // ---- GET /user/{id}: owner or ADMIN ----

    @Test
    void getById_owner_returnsProfile_withoutPasswordHash() throws Exception {
        when(userService.getById(aliceId)).thenReturn(sample(aliceId));

        mockMvc.perform(get("/user/{id}", aliceId).header("Authorization", alice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(aliceId.toString()))
                .andExpect(jsonPath("$.phoneNumber").value("+65 1234 5678"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void getById_otherUser_returns403() throws Exception {
        mockMvc.perform(get("/user/{id}", aliceId).header("Authorization", bob()))
                .andExpect(status().isForbidden());
        verify(userService, never()).getById(any());
    }

    @Test
    void getById_admin_canRead() throws Exception {
        when(userService.getById(aliceId)).thenReturn(sample(aliceId));

        mockMvc.perform(get("/user/{id}", aliceId).header("Authorization", admin()))
                .andExpect(status().isOk());
    }

    @Test
    void getById_unknown_returns404() throws Exception {
        when(userService.getById(aliceId)).thenThrow(new UserNotFoundException(aliceId));

        mockMvc.perform(get("/user/{id}", aliceId).header("Authorization", alice()))
                .andExpect(status().isNotFound());
    }

    // ---- GET /user: ADMIN only ----

    @Test
    void getAll_admin_returnsList() throws Exception {
        when(userService.getAll()).thenReturn(List.of(sample(aliceId), sample(bobId)));

        mockMvc.perform(get("/user").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void getAll_nonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/user").header("Authorization", alice())).andExpect(status().isForbidden());
        mockMvc.perform(get("/user").header("Authorization", bob())).andExpect(status().isForbidden());
        verify(userService, never()).getAll();
    }

    // ---- PUT /user/{id}: owner only ----

    @Test
    void update_owner_returnsUpdatedProfile() throws Exception {
        when(userService.update(eq(aliceId), any())).thenReturn(sample(aliceId));

        mockMvc.perform(put("/user/{id}", aliceId).header("Authorization", alice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Alice\",\"phoneNumber\":\"+65 1234 5678\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Alice"));
    }

    @Test
    void update_otherUserOrAdmin_returns403() throws Exception {
        String body = "{\"fullName\":\"Hacked\"}";
        mockMvc.perform(put("/user/{id}", aliceId).header("Authorization", bob())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/user/{id}", aliceId).header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        verify(userService, never()).update(any(), any());
    }

    // ---- DELETE /user/{id}: owner only ----

    @Test
    void delete_owner_returns204() throws Exception {
        mockMvc.perform(delete("/user/{id}", aliceId).header("Authorization", alice()))
                .andExpect(status().isNoContent());
        verify(userService).delete(aliceId, TestTokens.usernameFor(aliceId));
    }

    @Test
    void delete_otherUserOrAdmin_returns403() throws Exception {
        mockMvc.perform(delete("/user/{id}", aliceId).header("Authorization", bob()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/user/{id}", aliceId).header("Authorization", admin()))
                .andExpect(status().isForbidden());
        verify(userService, never()).delete(any(), any());
    }

    @Test
    void delete_ownAlreadyDeletedAccount_returns404() throws Exception {
        doThrow(new UserNotFoundException(aliceId)).when(userService).delete(eq(aliceId), any());

        mockMvc.perform(delete("/user/{id}", aliceId).header("Authorization", alice()))
                .andExpect(status().isNotFound());
    }

    // ---- no POST /user ----

    @Test
    void create_isNotExposed_onboardingIsTheOnlyWay() throws Exception {
        mockMvc.perform(post("/user").header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@y.com\",\"fullName\":\"X\",\"role\":\"PARTICIPANT\"}"))
                .andExpect(status().isMethodNotAllowed());
    }

    // ---- CORS: the browser frontend (default origin http://localhost:5173) ----

    @Test
    void corsPreflight_fromTheFrontend_isAllowed_withoutAToken() throws Exception {
        mockMvc.perform(options("/user/me")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void corsPreflight_fromAnotherOrigin_isRefused() throws Exception {
        mockMvc.perform(options("/user/me")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    // ---- /user/me: onboarding after a Cognito sign-up, and "my profile" ----

    /** A freshly signed-up Cognito user: valid token, no group yet. */
    private String newUser(UUID id) {
        return "Bearer " + TestTokens.access(id);
    }

    @Test
    void onboard_withATokenThatHasNoGroupYet_returns201() throws Exception {
        UUID newId = UUID.randomUUID();
        when(userService.onboard(eq(newId), eq(TestTokens.usernameFor(newId)), any())).thenReturn(sample(newId));

        mockMvc.perform(post("/user/me").header("Authorization", newUser(newId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Alice\",\"role\":\"PARTICIPANT\",\"phoneNumber\":\"+65 1234 5678\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(newId.toString()));
    }

    @Test
    void onboard_usesTheTokensSubject_notAnIdFromTheRequest() throws Exception {
        UUID newId = UUID.randomUUID();
        when(userService.onboard(eq(newId), eq(TestTokens.usernameFor(newId)), any())).thenReturn(sample(newId));

        mockMvc.perform(post("/user/me").header("Authorization", newUser(newId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + aliceId + "\",\"fullName\":\"Mallory\",\"role\":\"PARTICIPANT\"}"))
                .andExpect(status().isCreated());
        verify(userService).onboard(eq(newId), eq(TestTokens.usernameFor(newId)), any());
        verify(userService, never()).onboard(eq(aliceId), any(), any());
    }

    @Test
    void onboard_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/user/me").header("Authorization", newUser(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"+65 1234 5678\"}"))
                .andExpect(status().isBadRequest());
        verify(userService, never()).onboard(any(), any(), any());
    }

    @Test
    void onboard_withoutToken_returns401() throws Exception {
        mockMvc.perform(post("/user/me").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Alice\",\"role\":\"PARTICIPANT\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_returnsTheCallersProfile_or404BeforeOnboarding() throws Exception {
        when(userService.getById(aliceId)).thenReturn(sample(aliceId));
        mockMvc.perform(get("/user/me").header("Authorization", alice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(aliceId.toString()));

        UUID newId = UUID.randomUUID();
        when(userService.getById(newId)).thenThrow(new UserNotFoundException(newId));
        mockMvc.perform(get("/user/me").header("Authorization", newUser(newId)))
                .andExpect(status().isNotFound());
    }
}
