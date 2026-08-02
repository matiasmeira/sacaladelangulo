package com.matiasmeira.sacaladelangulo.caja.controller;

import com.matiasmeira.sacaladelangulo.caja.dto.ConsumirCodigoRequest;
import com.matiasmeira.sacaladelangulo.caja.dto.ConsumirCodigoResponse;
import com.matiasmeira.sacaladelangulo.caja.service.DispositivoCajaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint público (sin autenticación) consumido por la pantalla de emparejamiento del
 * front cuando alguien abre el link de un solo uso generado por el dueño/admin.
 */
@RestController
@RequestMapping("/api/v1/caja")
@RequiredArgsConstructor
public class CajaPublicoController {

    private final DispositivoCajaService dispositivoCajaService;

    @PostMapping("/emparejar")
    public ResponseEntity<ConsumirCodigoResponse> emparejar(
            @RequestBody @Valid ConsumirCodigoRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        // Misma fuente que RateLimitFilter (getRemoteAddr, no X-Forwarded-For): ver el
        // comentario de esa clase sobre por qué, mientras no haya un proxy de confianza
        // delante, confiar en un header enviado por el propio cliente sería bypasseable.
        String ip = httpRequest.getRemoteAddr();
        return ResponseEntity.ok(dispositivoCajaService.consumirCodigo(request.codigo(), ip, httpResponse));
    }
}
