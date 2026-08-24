package com.rag.documentChatBot;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {
    @Value("${app.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, LoginNotificationService notificationService) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/oauth2/**", "/login/**", "/api/auth/me").permitAll()
                .requestMatchers("/api/admin/logs/**").authenticated()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .anyRequest().authenticated())
            .oauth2Login(oauth -> oauth.successHandler(authenticationSuccessHandler(notificationService)))
            .logout(logout -> logout.logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)));
        return http.build();
    }

    @Bean
    AuthenticationSuccessHandler authenticationSuccessHandler(LoginNotificationService notificationService) {
        SimpleUrlAuthenticationSuccessHandler handler = new SimpleUrlAuthenticationSuccessHandler(frontendUrl + "/dashboard");
        handler.setAlwaysUseDefaultTargetUrl(true);
        return (request, response, authentication) -> {
            Authentication authenticated = authentication;
            if (authenticated.getPrincipal() instanceof OAuth2User user) {
                String name = user.getAttribute("name");
                String email = user.getAttribute("email");
                notificationService.sendLoginSuccess(name == null ? authenticated.getName() : name, email == null ? "unknown" : email);
            }
            handler.onAuthenticationSuccess(request, response, authenticated);
        };
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(frontendUrl));
        configuration.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @RestController
    static class AuthController {
        @GetMapping("/api/auth/me")
        Map<String, Object> currentUser(@AuthenticationPrincipal OAuth2User user) {
            if (user == null) {
                return Map.of();
            }
            Map<String, Object> profile = new HashMap<>();
            profile.put("name", user.getAttribute("name"));
            profile.put("email", user.getAttribute("email"));
            if (user.getAttribute("picture") != null) {
                profile.put("picture", user.getAttribute("picture"));
            }
            return profile;
        }
    }
}
