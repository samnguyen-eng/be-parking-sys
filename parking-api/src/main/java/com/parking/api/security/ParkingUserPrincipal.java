package com.parking.api.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;

/**
 * Authenticated principal with userId from JWT (avoids DB lookup per request).
 */
@Getter
public class ParkingUserPrincipal extends User {

    private final Long userId;

    public ParkingUserPrincipal(Long userId, String username, String password,
                                Collection<? extends GrantedAuthority> authorities) {
        super(username, password, authorities);
        this.userId = userId;
    }
}
