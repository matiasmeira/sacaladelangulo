package com.matiasmeira.sacaladelangulo.mails.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Procesa la baja de los emails de marketing a partir del token opaco del link (ver
 * Usuario.unsubscribeToken): no requiere autenticación, así que el token es la única
 * prueba de identidad aceptada acá.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class BajaMarketingService {

    private final UsuarioRepository usuarioRepository;

    public void darDeBaja(String token) {
        Usuario usuario = usuarioRepository.findByUnsubscribeToken(token)
                .orElseThrow(() -> new EntityNotFoundException("Token de baja inválido"));

        usuario.setAceptaMarketing(false);
        usuarioRepository.save(usuario);
    }
}
