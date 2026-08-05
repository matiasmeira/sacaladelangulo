package com.matiasmeira.sacaladelangulo.caja.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.caja.dto.ActivarLocalRequest;
import com.matiasmeira.sacaladelangulo.caja.dto.ActivarLocalResponse;
import com.matiasmeira.sacaladelangulo.caja.dto.ConsumirCodigoResponse;
import com.matiasmeira.sacaladelangulo.caja.dto.DispositivoCajaResponse;
import com.matiasmeira.sacaladelangulo.caja.dto.EmparejarRequest;
import com.matiasmeira.sacaladelangulo.caja.dto.EmparejarResponse;
import com.matiasmeira.sacaladelangulo.caja.model.CodigoEmparejamientoCaja;
import com.matiasmeira.sacaladelangulo.caja.model.DispositivoCaja;
import com.matiasmeira.sacaladelangulo.caja.repository.CodigoEmparejamientoCajaRepository;
import com.matiasmeira.sacaladelangulo.caja.repository.DispositivoCajaRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.core.ratelimit.RateLimitExceededException;
import com.matiasmeira.sacaladelangulo.core.ratelimit.RateLimiterService;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Gestión del ciclo de vida de los dispositivos de caja emparejados (ver
 * DispositivoCajaGate para la lectura/escritura de la cookie que estos métodos setean).
 * El token de dispositivo y el código de emparejamiento son secretos opacos de alta
 * entropía: nunca se persiste el valor crudo, solo su hash SHA-256 (rápido y determinístico,
 * a diferencia de BCrypt, que es deliberadamente lento y no sirve para una búsqueda por
 * igualdad en cada request de mostrador).
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class DispositivoCajaService {

    private static final int SECRETO_BYTES = 32;
    private static final int CONSUMIR_CODIGO_INTENTOS_MAXIMOS = 10;
    private static final long CONSUMIR_CODIGO_VENTANA_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final String MENSAJE_CODIGO_INVALIDO = "Código de emparejamiento inválido";
    private static final String MENSAJE_DISPOSITIVO_NO_AUTORIZADO = "Dispositivo no autorizado";

    private final DispositivoCajaRepository dispositivoCajaRepository;
    private final CodigoEmparejamientoCajaRepository codigoEmparejamientoCajaRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;
    private final RateLimiterService rateLimiterService;
    private final RegistroAuditoriaService registroAuditoriaService;

    @Value("${caja.dispositivo-expiration-millis:7776000000}")
    private long dispositivoExpirationMillis;

    @Value("${caja.codigo-emparejamiento-ttl-millis:600000}")
    private long codigoEmparejamientoTtlMillis;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    public ActivarLocalResponse activarLocal(Long establecimientoId, String email, ActivarLocalRequest request, HttpServletResponse response) {
        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        Usuario actor = autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        String tokenCrudo = generarSecretoAlto();
        DispositivoCaja dispositivo = DispositivoCaja.builder()
                .establecimiento(establecimiento)
                .label(request == null ? null : request.label())
                .tokenHash(hash(tokenCrudo))
                .creadoPor(actor)
                .build();

        DispositivoCaja dispositivoGuardado = dispositivoCajaRepository.save(dispositivo);
        log.info("Dispositivo de caja activado. ID: {}, Establecimiento: {}", dispositivoGuardado.getId(), establecimientoId);

        DispositivoCajaGate.setCookie(response, tokenCrudo, dispositivoExpirationMillis);
        registroAuditoriaService.registrarDispositivo(actor, establecimiento, AccionAuditoria.ACTIVAR_DISPOSITIVO_CAJA,
                dispositivoGuardado.getId(), "Dispositivo activado: " + dispositivoGuardado.getLabel());

        return new ActivarLocalResponse(dispositivoGuardado.getId(), dispositivoGuardado.getLabel());
    }

    public EmparejarResponse emparejar(Long establecimientoId, String email, EmparejarRequest request) {
        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        Usuario actor = autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        String codigoCrudo = generarSecretoAlto();
        LocalDateTime expiraEn = LocalDateTime.now().plus(Duration.ofMillis(codigoEmparejamientoTtlMillis));
        CodigoEmparejamientoCaja codigo = CodigoEmparejamientoCaja.builder()
                .codigoHash(hash(codigoCrudo))
                .establecimiento(establecimiento)
                .label(request == null ? null : request.label())
                .creadoPor(actor)
                .expiraEn(expiraEn)
                .build();

        codigoEmparejamientoCajaRepository.save(codigo);
        log.info("Código de emparejamiento de caja generado. Establecimiento: {}", establecimientoId);

        String urlEmparejamiento = frontendUrl + "/caja/emparejar?codigo=" + codigoCrudo;
        return new EmparejarResponse(codigoCrudo, expiraEn, urlEmparejamiento);
    }

    @Transactional(readOnly = true)
    public List<DispositivoCajaResponse> listar(Long establecimientoId, String email) {
        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        return dispositivoCajaRepository.findByEstablecimientoIdAndActivoTrue(establecimientoId).stream()
                .map(d -> new DispositivoCajaResponse(d.getId(), d.getLabel(), d.getCreatedAt(), d.getLastUsedAt()))
                .toList();
    }

    public void revocar(Long establecimientoId, Long dispositivoId, String email) {
        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        Usuario actor = autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        DispositivoCaja dispositivo = dispositivoCajaRepository.findByIdAndEstablecimientoId(dispositivoId, establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Dispositivo no encontrado"));

        dispositivo.setActivo(false);
        dispositivoCajaRepository.save(dispositivo);
        log.info("Dispositivo de caja revocado. ID: {}, Establecimiento: {}", dispositivoId, establecimientoId);

        registroAuditoriaService.registrarDispositivo(actor, establecimiento, AccionAuditoria.REVOCAR_DISPOSITIVO_CAJA,
                dispositivo.getId(), "Dispositivo revocado: " + dispositivo.getLabel());
    }

    /**
     * Consume un código de emparejamiento y crea el dispositivo correspondiente. Un
     * código inexistente, expirado o ya usado devuelve siempre la misma excepción
     * genérica: distinguir el motivo convertiría este endpoint público en un oráculo
     * del estado del código (mismo criterio que AuthService.authenticate).
     */
    public ConsumirCodigoResponse consumirCodigo(String codigoCrudo, String ip, HttpServletResponse response) {
        if (!rateLimiterService.tryConsume("emparejar-caja:" + ip, CONSUMIR_CODIGO_INTENTOS_MAXIMOS, CONSUMIR_CODIGO_VENTANA_MILLIS)) {
            throw new RateLimitExceededException("Demasiados intentos de emparejamiento. Intente nuevamente en unos minutos.");
        }

        CodigoEmparejamientoCaja codigo = codigoEmparejamientoCajaRepository.findByCodigoHash(hash(codigoCrudo))
                .orElseThrow(() -> new AccessDeniedException(MENSAJE_CODIGO_INVALIDO));

        if (Boolean.TRUE.equals(codigo.getUsado()) || codigo.getExpiraEn().isBefore(LocalDateTime.now())) {
            throw new AccessDeniedException(MENSAJE_CODIGO_INVALIDO);
        }

        codigo.setUsado(true);
        codigoEmparejamientoCajaRepository.save(codigo);

        String tokenCrudo = generarSecretoAlto();
        DispositivoCaja dispositivo = DispositivoCaja.builder()
                .establecimiento(codigo.getEstablecimiento())
                .label(codigo.getLabel())
                .tokenHash(hash(tokenCrudo))
                .creadoPor(codigo.getCreadoPor())
                .build();

        DispositivoCaja dispositivoGuardado = dispositivoCajaRepository.save(dispositivo);
        log.info("Dispositivo de caja emparejado. ID: {}, Establecimiento: {}",
                dispositivoGuardado.getId(), codigo.getEstablecimiento().getId());

        DispositivoCajaGate.setCookie(response, tokenCrudo, dispositivoExpirationMillis);
        registroAuditoriaService.registrarDispositivo(codigo.getCreadoPor(), codigo.getEstablecimiento(),
                AccionAuditoria.EMPAREJAR_DISPOSITIVO_CAJA, dispositivoGuardado.getId(),
                "Dispositivo emparejado: " + dispositivoGuardado.getLabel());

        return new ConsumirCodigoResponse(codigo.getEstablecimiento().getId(), dispositivoGuardado.getLabel());
    }

    /**
     * Valida el token crudo leído de la cookie de dispositivo. No hace falta versionado
     * (a diferencia de tokenVersion en Usuario): revocar es simplemente marcar `activo=false`.
     */
    @Transactional
    public DispositivoCaja validarToken(String tokenCrudo) {
        DispositivoCaja dispositivo = dispositivoCajaRepository.findByTokenHash(hash(tokenCrudo))
                .orElseThrow(() -> new AccessDeniedException(MENSAJE_DISPOSITIVO_NO_AUTORIZADO));

        if (!Boolean.TRUE.equals(dispositivo.getActivo())) {
            throw new AccessDeniedException(MENSAJE_DISPOSITIVO_NO_AUTORIZADO);
        }

        dispositivo.setLastUsedAt(LocalDateTime.now());
        dispositivoCajaRepository.save(dispositivo);
        return dispositivo;
    }

    private String generarSecretoAlto() {
        byte[] bytes = new byte[SECRETO_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }

    private Establecimiento buscarEstablecimientoPorId(Long establecimientoId) {
        return establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
    }

}
