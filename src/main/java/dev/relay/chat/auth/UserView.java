package dev.relay.chat.auth;

public record UserView(Long id, String username) {
    public static UserView from(UserAccount user) {
        return new UserView(user.getId(), user.getUsername());
    }
}
