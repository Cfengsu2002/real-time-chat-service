package dev.relay.chat.realtime;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OriginPolicy {
    private final Set<String> allowed;
    private final String[] configured;

    public OriginPolicy(@Value("${app.realtime.allowed-origins}") String origins) {
        configured = Arrays.stream(origins.split(",")).map(String::trim).toArray(String[]::new);
        allowed = Arrays.stream(configured).map(OriginPolicy::normalize)
                .collect(Collectors.toUnmodifiableSet());
        if (allowed.isEmpty() || allowed.contains("")) {
            throw new IllegalArgumentException("APP_ALLOWED_ORIGINS must contain explicit HTTP(S) origins");
        }
    }

    public boolean allows(String origin) {
        return origin != null && allowed.contains(normalize(origin));
    }

    public String[] configuredOrigins() {
        return configured.clone();
    }

    private static String normalize(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) || uri.getPort() > 65535) {
                return "";
            }
            int port = uri.getPort() == -1 ? ("https".equalsIgnoreCase(scheme) ? 443 : 80) : uri.getPort();
            return scheme.toLowerCase(java.util.Locale.ROOT) + "://"
                    + uri.getHost().toLowerCase(java.util.Locale.ROOT) + ":" + port;
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }
}
