package com.matiasmeira.sacaladelangulo.mails.controller;

import com.matiasmeira.sacaladelangulo.mails.dto.BajaMarketingRequest;
import com.matiasmeira.sacaladelangulo.mails.service.BajaMarketingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints públicos relacionados con emails de marketing, accesibles sin autenticación
 * (ver la excepción explícita en SecurityConfig para /baja).
 */
@RestController
@RequestMapping("/api/v1/mails")
@RequiredArgsConstructor
public class MailsController {

    private final BajaMarketingService bajaMarketingService;

    @PostMapping("/baja")
    public ResponseEntity<Void> darDeBaja(@RequestBody @Valid BajaMarketingRequest request) {
        bajaMarketingService.darDeBaja(request.token());
        return ResponseEntity.noContent().build();
    }
}
