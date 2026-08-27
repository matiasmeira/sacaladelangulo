package com.matiasmeira.sacaladelangulo.core.config.security;

import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.auth.service.JwtService;
import com.matiasmeira.sacaladelangulo.auth.service.UsuarioUserDetailsMapper;
import com.matiasmeira.sacaladelangulo.core.idempotencia.IdempotencyFilter;
import com.matiasmeira.sacaladelangulo.core.idempotencia.SolicitudIdempotenteRepository;
import com.matiasmeira.sacaladelangulo.core.ratelimit.RateLimitFilter;
import com.matiasmeira.sacaladelangulo.core.ratelimit.RateLimiterService;
import com.matiasmeira.sacaladelangulo.core.trazabilidad.TraceIdFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Configuración de seguridad de Spring Security sin WebSecurityConfigurerAdapter.
 */
@Configuration
@RequiredArgsConstructor
@EnableMethodSecurity
public class SecurityConfig {

    private final UsuarioRepository usuarioRepository;
    private final JwtService jwtService;

    /**
     * Externalizado (a diferencia de antes, que estaba hardcodeado en el código) para
     * poder cambiar los orígenes permitidos por entorno sin recompilar (ver M9 en la
     * auditoría).
     */
    @Value("${app.cors.allowed-origins}")
    private java.util.List<String> allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RateLimitFilter rateLimitFilter,
            IdempotencyFilter idempotencyFilter,
            RestAuthenticationEntryPoint restAuthenticationEntryPoint,
            RestAccessDeniedHandler restAccessDeniedHandler) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        .accessDeniedHandler(restAccessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        // Excepción puntual antes de la regla general de abajo: logout
                        // necesita saber quién es el usuario autenticado (ver B3 en la auditoría).
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/auth/logout").authenticated()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/publico/**").permitAll()
                        // GET /empleados/activos es la puerta de entrada del Modo Caja: la PC del
                        // mostrador muestra los nombres del local ANTES de que ningún empleado
                        // inicie sesión, así que en ese momento no hay ni puede haber un JWT.
                        // Exigir uno obligaría a dejar la credencial del dueño en la tablet, que es
                        // exactamente lo que este modo evita.
                        //
                        // Que sea permitAll acá NO lo deja abierto: la autorización real la hace
                        // DispositivoCajaGate dentro del controller, que exige la cookie de
                        // dispositivo (token opaco, guardado hasheado) y además verifica que ese
                        // dispositivo pertenezca a ESTE establecimiento. Es un criterio más estricto
                        // que "cualquier usuario autenticado". authorizeHttpRequests no puede
                        // inspeccionar la validez de una cookie, por eso la decisión vive en el
                        // controller y acá sólo se le saca de encima el filtro de JWT.
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/v1/establecimientos/*/empleados/activos").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/caja/emparejar").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/webhooks/resend").permitAll()
                        // Sin autenticación a propósito: el link de "darme de baja" de un email de
                        // marketing lo identifica el token opaco del body, no una sesión (ver Fase 6).
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/mails/baja").permitAll()
                        .anyRequest().authenticated()
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(idempotencyFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource() {
        org.springframework.web.cors.CorsConfiguration configuration = new org.springframework.web.cors.CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type", "Cache-Control", "Idempotency-Key"));
        // Sin esto el navegador recibe X-Trace-Id pero no deja que el JS lo lea (por CORS,
        // solo los headers "simples" son visibles salvo que se los exponga). Es lo que
        // permite que una pantalla de error muestre el código para reportar (ver TraceIdFilter).
        configuration.setExposedHeaders(java.util.List.of(TraceIdFilter.TRACE_ID_HEADER, "Idempotency-Replayed"));
        configuration.setAllowCredentials(true);

        org.springframework.web.cors.UrlBasedCorsConfigurationSource source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(UserDetailsService userDetailsService) {
        return new JwtAuthenticationFilter(jwtService, userDetailsService);
    }

    @Bean
    public RateLimitFilter rateLimitFilter(RateLimiterService rateLimiterService) {
        return new RateLimitFilter(rateLimiterService);
    }

    @Bean
    public IdempotencyFilter idempotencyFilter(SolicitudIdempotenteRepository solicitudIdempotenteRepository) {
        return new IdempotencyFilter(solicitudIdempotenteRepository);
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> usuarioRepository.findByEmail(username == null ? null : username.trim().toLowerCase())
                .map(UsuarioUserDetailsMapper::map)
                .orElseThrow(() -> new org.springframework.security.core.userdetails.UsernameNotFoundException("Usuario no encontrado"));
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService());
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
