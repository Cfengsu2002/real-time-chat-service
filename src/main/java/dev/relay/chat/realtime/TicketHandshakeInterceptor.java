package dev.relay.chat.realtime;

import dev.relay.chat.api.ApiException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class TicketHandshakeInterceptor implements HandshakeInterceptor {
    public static final String PRINCIPAL_ATTRIBUTE = "chatPrincipal";
    private final TicketService tickets;

    public TicketHandshakeInterceptor(TicketService tickets) { this.tickets = tickets; }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        if (!sameOrigin(request)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
        List<String> values = query.get("ticket");
        if (values == null || values.size() != 1) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        try {
            attributes.put(PRINCIPAL_ATTRIBUTE, tickets.consume(values.get(0)));
            return true;
        } catch (ApiException ex) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    private boolean sameOrigin(ServerHttpRequest request) {
        String originValue = request.getHeaders().getOrigin();
        if (originValue == null) { return false; }
        try {
            URI origin = URI.create(originValue);
            URI target = request.getURI();
            return ("http".equalsIgnoreCase(origin.getScheme()) || "https".equalsIgnoreCase(origin.getScheme()))
                    && origin.getUserInfo() == null && origin.getQuery() == null && origin.getFragment() == null
                    && (origin.getPath() == null || origin.getPath().isEmpty())
                    && origin.getScheme().equalsIgnoreCase(target.getScheme())
                    && origin.getHost() != null && origin.getHost().equalsIgnoreCase(target.getHost())
                    && effectivePort(origin) == effectivePort(target);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private int effectivePort(URI uri) {
        return uri.getPort() == -1 ? (Objects.equals(uri.getScheme(), "https") ? 443 : 80) : uri.getPort();
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) { }
}
