package com.matiasmeira.sacaladelangulo.mails.controller;

import com.matiasmeira.sacaladelangulo.mails.dto.EnviarOfertaRequest;
import com.matiasmeira.sacaladelangulo.mails.service.OfertaMarketingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint de administración para disparar un broadcast de oferta de marketing a todos
 * los usuarios con opt-in. Requiere autenticación (cae bajo el anyRequest().authenticated()
 * de SecurityConfig) y la autorización de rol ADMIN se valida manualmente dentro del
 * servicio, mismo patrón que el resto del código (no se usa @PreAuthorize en este repo).
 */
@RestController
@RequestMapping("/api/v1/admin/mails")
@RequiredArgsConstructor
public class AdminMailsController {

    private final OfertaMarketingService ofertaMarketingService;

    /**
     * 202 Accepted en vez de 200/204: el envío efectivo ocurre de forma asíncrona
     * después de que esta respuesta ya se devolvió (ver OfertaMarketingBatchSender).
     */
    @PostMapping("/oferta")
    public ResponseEntity<Void> enviarOferta(@RequestBody @Valid EnviarOfertaRequest request,
                                              @AuthenticationPrincipal UserDetails userDetails) {
        ofertaMarketingService.enviarOferta(request, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
