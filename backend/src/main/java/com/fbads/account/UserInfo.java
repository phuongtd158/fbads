package com.fbads.account;

public record UserInfo(long id, String username, String name) {
    public static UserInfo of(User u) { return new UserInfo(u.getId(), u.getUsername(), u.getName()); }
}
