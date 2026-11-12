package auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import auth.cognito.CognitoGateway;

@SpringBootTest
@ActiveProfiles("test")
class AuthServiceApplicationTests {

    // Keeps AdminBootstrap from calling AWS on startup.
    @MockitoBean
    private CognitoGateway cognitoGateway;

    @Test
    void contextLoads() {
        // Sanity check that the Spring context wires up correctly.
    }
}
