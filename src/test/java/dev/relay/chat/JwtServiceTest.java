package dev.relay.chat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.relay.chat.api.ApiException;
import dev.relay.chat.auth.ChatPrincipal;
import dev.relay.chat.auth.JwtService;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {
    private static final String SECRET = "test-only-secret-key-at-least-32-bytes";

    @Test
    void secretStrengthIsMeasuredInUtf8Bytes() {
        assertThatThrownBy(() -> new JwtService("short-key", 3600)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new JwtService("密".repeat(11), 3600)).isNotNull();
    }

    @Test
    void signedTokensPreserveIdentityAndExpiration() throws Exception {
        Instant expiry = Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        JwtService jwt = new JwtService(SECRET, 3600);
        ChatPrincipal principal = jwt.verify(token(SECRET, "relay-chat", expiry));
        assertThat(principal.id()).isEqualTo(42);
        assertThat(principal.username()).isEqualTo("alex");
        assertThat(principal.expiresAt()).isEqualTo(expiry);
    }

    @Test
    void expiredWrongIssuerAndWrongSignatureTokensAreRejected() throws Exception {
        JwtService jwt = new JwtService(SECRET, 3600);
        String expired = token(SECRET, "relay-chat", Instant.now().minusSeconds(1));
        String wrongIssuer = token(SECRET, "another-app", Instant.now().plusSeconds(60));
        String wrongSignature = token("different-test-key-with-at-least-32-bytes", "relay-chat", Instant.now().plusSeconds(60));
        for (String value : new String[]{expired, wrongIssuer, wrongSignature, "malformed", "", null}) {
            assertThatThrownBy(() -> jwt.verify(value)).isInstanceOf(ApiException.class);
        }
    }

    @Test
    void invalidTokenLifetimesAreRejected() {
        assertThatThrownBy(() -> new JwtService(SECRET, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JwtService(SECRET, 604801)).isInstanceOf(IllegalArgumentException.class);
    }

    private String token(String key, String issuer, Instant expiresAt) throws Exception {
        SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
                .issuer(issuer).subject("42").claim("username", "alex")
                .expirationTime(Date.from(expiresAt)).build());
        token.sign(new MACSigner(key));
        return token.serialize();
    }
}
