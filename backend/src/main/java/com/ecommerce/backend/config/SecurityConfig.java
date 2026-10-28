package com.ecommerce.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${cors.allowed-origins:http://localhost:4200,http://localhost:3000}")
    private String allowedOrigins;

    @Value("${supabase.jwt.issuer}")
    private String jwtIssuer;

    @Value("${supabase.jwt.jwk-set-uri}")
    private String jwkSetUri;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(headers -> headers.frameOptions(frame -> frame.disable()))
            .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
            .authorizeHttpRequests(auth -> auth
                // Preflight CORS siempre permitido
                .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                // Documentación Swagger / OpenAPI
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/api/v1/admin/acceso").authenticated()
                .requestMatchers("/api/v1/admin/**").access((authentication, context) -> {
                    var principal = authentication.get();
                    if (!(principal instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken token)) {
                        org.slf4j.LoggerFactory.getLogger(SecurityConfig.class).warn(
                                "[ADMIN-SECURITY] Principal is not JwtAuthenticationToken: {}",
                                principal != null ? principal.getClass().getName() : "null");
                        return new org.springframework.security.authorization.AuthorizationDecision(false);
                    }
                    boolean allowed = AdminPermissions.allowed(token.getToken());
                    return new org.springframework.security.authorization.AuthorizationDecision(allowed);
                })
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/pagos/webhook").permitAll()
                .requestMatchers("/api/v1/pagos/**").authenticated()
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/productos/*/resenas").authenticated()
                .requestMatchers("/api/v1/ayuda/consultas").authenticated()
                .requestMatchers("/api/v1/cuenta/**", "/api/v1/pedidos/**", "/api/v1/favoritos/**",
                        "/api/v1/carrito/vincular", "/api/v1/pc-builder/configuraciones/**",
                        "/api/v1/pc-builder/configuraciones", "/api/v1/pc-builder/aplicar-cambio").authenticated()
                // Catálogo, filtros, asesoría, carrito anónimo y opciones de checkout siguen siendo públicos.
                .requestMatchers("/api/v1/**").permitAll()
                // Cualquier otra solicitud permitida durante la fase de base esencial
                .anyRequest().permitAll()
            );

        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        // Supabase's signing keys use ES256. Accept only the explicitly supported
        // asymmetric algorithms and continue verifying the issuer, signature and expiry.
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(jwtIssuer));
        return decoder;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        java.util.Set<String> origins = new java.util.LinkedHashSet<>(
                Arrays.stream(allowedOrigins.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList()
        );
        origins.add("http://localhost:*");
        origins.add("http://*.localhost:*");
        origins.add("http://127.0.0.1:*");
        configuration.setAllowedOriginPatterns(new java.util.ArrayList<>(origins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Authorization", "Content-Disposition", "X-Total-Count"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
