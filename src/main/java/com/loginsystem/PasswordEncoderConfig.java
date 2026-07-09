package com.loginsystem;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Provides the BCryptPasswordEncoder bean for the entire application.
 * NOTE: spring-security-crypto is added WITHOUT spring-boot-starter-security,
 * so no HTTP security filters are auto-configured. All existing endpoints
 * remain openly accessible while passwords are now safely hashed at rest.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
