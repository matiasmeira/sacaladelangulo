package com.matiasmeira.sacaladelangulo.core.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("GlobalExceptionHandler - Mapeo de errores de cliente comunes (ver M22)")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("handleDataIntegrityViolation_ConSqlState23P01_Devuelve409DeConcurrencia")
    void handleDataIntegrityViolation_ConSqlState23P01_Devuelve409DeConcurrencia() {
        // Se replica el anidamiento real: Spring envuelve la SQLException de Postgres, así
        // que el SQLSTATE solo aparece caminando la cadena de causas.
        java.sql.SQLException sqlEx = new java.sql.SQLException("conflicting key value violates exclusion constraint", "23P01");
        org.springframework.dao.DataIntegrityViolationException ex =
                new org.springframework.dao.DataIntegrityViolationException("wrapper", sqlEx);

        ResponseEntity<Map<String, String>> response = handler.handleDataIntegrityViolation(ex);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("Conflicto de concurrencia", response.getBody().get("error"));
    }

    @Test
    @DisplayName("handleDataIntegrityViolation_OtraViolacion_Devuelve409SinFiltrarElConstraint")
    void handleDataIntegrityViolation_OtraViolacion_Devuelve409SinFiltrarElConstraint() {
        java.sql.SQLException sqlEx = new java.sql.SQLException(
                "new row violates check constraint \"chk_gastos_monto_positivo\"", "23514");
        org.springframework.dao.DataIntegrityViolationException ex =
                new org.springframework.dao.DataIntegrityViolationException("wrapper", sqlEx);

        ResponseEntity<Map<String, String>> response = handler.handleDataIntegrityViolation(ex);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("Conflicto de integridad", response.getBody().get("error"));
        // El nombre del constraint no debe filtrarse al cliente.
        assertEquals(false, response.getBody().get("message").contains("chk_gastos_monto_positivo"));
    }

    @Test
    @DisplayName("handleHttpMessageNotReadableException_Devuelve400")
    void handleHttpMessageNotReadableException_Devuelve400() {
        HttpMessageNotReadableException ex = mock(HttpMessageNotReadableException.class);

        ResponseEntity<Map<String, String>> response = handler.handleHttpMessageNotReadableException(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    @DisplayName("handleNumberFormatException_Devuelve400ConMensajeGenerico")
    void handleNumberFormatException_Devuelve400ConMensajeGenerico() {
        NumberFormatException ex = new NumberFormatException("For input string: \"abc\"");

        ResponseEntity<Map<String, String>> response = handler.handleNumberFormatException(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Parámetro numérico inválido", response.getBody().get("error"));
    }

    @Test
    @DisplayName("handleMethodArgumentTypeMismatchException_Devuelve400ConNombreDelParametro")
    void handleMethodArgumentTypeMismatchException_Devuelve400ConNombreDelParametro() {
        MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
        when(ex.getName()).thenReturn("id");

        ResponseEntity<Map<String, String>> response = handler.handleMethodArgumentTypeMismatchException(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Parámetro 'id' inválido", response.getBody().get("error"));
    }

    @Test
    @DisplayName("handleHttpRequestMethodNotSupportedException_Devuelve405ConElMetodoRechazado")
    void handleHttpRequestMethodNotSupportedException_Devuelve405ConElMetodoRechazado() {
        HttpRequestMethodNotSupportedException ex = new HttpRequestMethodNotSupportedException("DELETE");

        ResponseEntity<Map<String, String>> response = handler.handleHttpRequestMethodNotSupportedException(ex);

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertEquals("Método DELETE no soportado para este endpoint", response.getBody().get("error"));
    }

    @Test
    @DisplayName("handleNoHandlerFoundException_Devuelve404")
    void handleNoHandlerFoundException_Devuelve404() {
        NoHandlerFoundException ex = new NoHandlerFoundException(HttpMethod.GET.name(), "/ruta-inexistente", new HttpHeaders());

        ResponseEntity<Map<String, String>> response = handler.handleNoHandlerFoundException(ex);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
