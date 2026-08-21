package com.mcortes.authcoremc.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Argon2id password hashing (docs/ARQUITECTURA.md decision 5: OWASP's
 * current recommendation, more resistant to GPU/ASIC attacks than bcrypt).
 * Parameters match Spring Security's own recommended defaults for
 * {@code Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()}.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}
