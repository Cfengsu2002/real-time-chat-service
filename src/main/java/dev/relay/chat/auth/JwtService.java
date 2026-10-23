package dev.relay.chat.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.relay.chat.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private static final String ISSUER = "relay-chat";
    private final byte[] secret;
    private final long ttlSeconds;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.ttl-seconds}") long ttlSeconds) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        if (this.secret.length < 32) {
            throw new IllegalArgumentException("JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        if (ttlSeconds < 60 || ttlSeconds > 604800) {
            throw new IllegalArgumentException("JWT_TTL_SECONDS must be between 60 and 604800");
        }
        this.ttlSeconds = ttlSeconds;
    }

    public TokenResult issue(UserAccount user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(ttlSeconds).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject(user.getId().toString())
                .claim("username", user.getUsername()).issueTime(Date.from(now)).expirationTime(Date.from(expiresAt)).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), claims);
        try {
            jwt.sign(new MACSigner(secret));
        } catch (JOSEException ex) {
            throw new IllegalStateException("Cannot sign authentication token", ex);
        }
        return new TokenResult(jwt.serialize(), UserView.from(user), expiresAt);
    }

    public ChatPrincipal verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(new MACVerifier(secret))) {
                throw invalid();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!ISSUER.equals(claims.getIssuer()) || claims.getExpirationTime() == null
                    || !claims.getExpirationTime().toInstant().isAfter(Instant.now())) {
                throw invalid();
            }
            long id = Long.parseLong(claims.getSubject());
            String username = claims.getStringClaim("username");
            if (id <= 0 || username == null || !username.matches("[a-z0-9_]{3,24}")) {
                throw invalid();
            }
            return new ChatPrincipal(id, username, claims.getExpirationTime().toInstant());
        } catch (ParseException | JOSEException | NumberFormatException ex) {
            throw invalid();
        }
    }

    private ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "invalid_token", "Authentication token is invalid or expired");
    }

    public record TokenResult(String token, UserView user, Instant expiresAt) { }
}
