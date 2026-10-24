package dev.relay.chat.realtime;

import dev.relay.chat.auth.ChatPrincipal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class TicketHandshakeInterceptor implements HandshakeInterceptor {
    public static final String PRINCIPAL_ATTRIBUTE = "relay.principal";
    private final OriginPolicy origins;
    private final TicketService tickets;

    public TicketHandshakeInterceptor(OriginPolicy origins, TicketService tickets) {
        this.origins = origins;
        this.tickets = tickets;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        // 先检查来源，拒绝恶意页面时不消耗正常客户端的票据。
        List<String> originHeaders = request.getHeaders().get("Origin");
        if (originHeaders == null || originHeaders.size() != 1 || !origins.allows(originHeaders.get(0))) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        List<String> values = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().get("ticket");
        if (values == null || values.size() != 1) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        try {
            Optional<ChatPrincipal> principal = tickets.consume(values.get(0));
            if (principal.isEmpty()) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            attributes.put(PRINCIPAL_ATTRIBUTE, principal.orElseThrow());
            return true;
        } catch (DataAccessException exception) {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) { }
}
