package com.matiasmeira.sacaladelangulo.core.exception;

import com.matiasmeira.sacaladelangulo.core.ratelimit.RateLimitExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleEntityNotFoundException(EntityNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", ex.getMessage()));
    }

    /**
     * NumberFormatException extiende IllegalArgumentException: sin este handler más
     * específico (Spring resuelve al @ExceptionHandler más cercano en la jerarquía),
     * un parseo manual sin capturar (Integer.parseInt, etc.) filtraría el mensaje crudo
     * de la JDK a través del handler genérico de abajo. Hoy no hay ningún parseo manual
     * en el proyecto, pero esto lo cubre igual como defensa en profundidad (ver M23).
     */
    @ExceptionHandler(NumberFormatException.class)
    public ResponseEntity<Map<String, String>> handleNumberFormatException(NumberFormatException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Parámetro numérico inválido"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgumentException(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(EstablecimientosActivosException.class)
    public ResponseEntity<Map<String, String>> handleEstablecimientosActivosException(EstablecimientosActivosException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(LimiteEstablecimientosException.class)
    public ResponseEntity<Map<String, String>> handleLimiteEstablecimientosException(LimiteEstablecimientosException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, String>> handleBadCredentialsException(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Credenciales inválidas"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex) {
        Map<String, String> errores = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                errores.put(error.getField(), error.getDefaultMessage())
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errores);
    }

    /**
     * Decisión consciente (ver B16 en la auditoría): este handler atiende tanto el
     * AccessDeniedException interno de Spring Security (falla de @PreAuthorize, mensaje
     * genérico tipo "Access Denied", sin datos sensibles) como el que lanza el código de
     * negocio en decenas de lugares (validarPropietarioOAdmin y similares) con mensajes
     * propios ("No autorizado en este establecimiento", etc.) que la API expone a
     * propósito. Fijar acá un mensaje único rompería esos mensajes de negocio en todo el
     * proyecto a cambio de desacoplar un caso de bajo riesgo real; se optó por no tocarlo.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDeniedException(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(TokenInvalidoException.class)
    public ResponseEntity<Map<String, String>> handleTokenInvalidoException(TokenInvalidoException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(TokenExpiradoException.class)
    public ResponseEntity<Map<String, String>> handleTokenExpiradoException(TokenExpiradoException ex) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(WebhookFirmaInvalidaException.class)
    public ResponseEntity<Map<String, String>> handleWebhookFirmaInvalidaException(WebhookFirmaInvalidaException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(JugadorBloqueadoException.class)
    public ResponseEntity<Map<String, String>> handleJugadorBloqueadoException(JugadorBloqueadoException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(ReservaExpiradaException.class)
    public ResponseEntity<Map<String, String>> handleReservaExpiradaException(ReservaExpiradaException ex) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, String>> handleRateLimitExceededException(RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleHttpMessageNotReadableException(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Cuerpo de la petición inválido o mal formado"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Parámetro '" + ex.getName() + "' inválido"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> handleHttpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Map.of("error", "Método " + ex.getMethod() + " no soportado para este endpoint"));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Map<String, String>> handleNoHandlerFoundException(NoHandlerFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Recurso no encontrado"));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> handleOptimisticLockingFailure(ObjectOptimisticLockingFailureException ex) {
        Map<String, String> errorResponse = new LinkedHashMap<>();
        errorResponse.put("error", "Conflicto de concurrencia");
        errorResponse.put("message", "La cancha acaba de ser reservada o modificada por otro usuario. Por favor, actualiza la disponibilidad e intenta nuevamente.");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
    }

    /**
     * Violaciones de integridad que llegan desde la base (no desde la validación de Java).
     *
     * <p>El caso que motivó este handler es el constraint de exclusión
     * {@code excl_reservas_solapadas} (ver V10): si dos transacciones logran insertar
     * reservas solapadas sobre la misma cancha, Postgres rechaza la segunda con SQLSTATE
     * 23P01 (exclusion_violation). Sin este handler el usuario veía un 500 genérico, cuando
     * en realidad es el mismo conflicto de concurrencia que ya se comunica como 409 en
     * handleOptimisticLockingFailure — y por eso comparte el mensaje.
     *
     * <p>El resto de las violaciones de integridad (UNIQUE, FK, los CHECK de V12) también
     * caen acá, pero con un mensaje genérico: son estados que la validación de Java ya
     * debería haber frenado antes, así que si llegan a la base es un bug nuestro. Se loguea
     * con el detalle para poder diagnosticarlo, pero NO se expone al cliente (el nombre del
     * constraint filtraría estructura interna del esquema; ver server.error.include-message
     * =never en el perfil prod, misma filosofía).
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrityViolation(
            org.springframework.dao.DataIntegrityViolationException ex) {
        Map<String, String> errorResponse = new LinkedHashMap<>();
        if (esViolacionDeExclusion(ex)) {
            log.warn("Constraint de exclusión rechazó un solapamiento de reservas", ex);
            errorResponse.put("error", "Conflicto de concurrencia");
            errorResponse.put("message", "La cancha acaba de ser reservada o modificada por otro usuario. Por favor, actualiza la disponibilidad e intenta nuevamente.");
        } else {
            log.error("Violación de integridad no contemplada al persistir", ex);
            errorResponse.put("error", "Conflicto de integridad");
            errorResponse.put("message", "No se pudo guardar la operación porque entra en conflicto con datos existentes.");
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
    }

    /**
     * Recorre la cadena de causas buscando el SQLSTATE 23P01 (exclusion_violation). Hay que
     * caminarla porque Spring envuelve la SQLException original en la jerarquía de
     * DataAccessException (y Hibernate la envuelve a su vez en ConstraintViolationException),
     * así que el SQLSTATE no está disponible en la excepción de más afuera.
     */
    private boolean esViolacionDeExclusion(Throwable ex) {
        for (Throwable causa = ex; causa != null; causa = causa.getCause()) {
            if (causa instanceof java.sql.SQLException sqlEx && "23P01".equals(sqlEx.getSQLState())) {
                return true;
            }
            if (causa.getCause() == causa) {
                break;
            }
        }
        return false;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpectedException(Exception ex) {
        log.error("Error no controlado procesando la petición", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Ocurrió un error interno. Por favor, intenta nuevamente más tarde."));
    }
}
