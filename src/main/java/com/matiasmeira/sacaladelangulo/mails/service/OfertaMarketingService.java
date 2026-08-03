package com.matiasmeira.sacaladelangulo.mails.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.mails.dto.EnviarOfertaRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Autoriza y dispara el broadcast de una oferta a todos los usuarios con opt-in de
 * marketing. El envío en sí se delega a OfertaMarketingBatchSender (bean separado,
 * inyectado) porque su método @Async solo corre de verdad async si se invoca a través
 * del proxy de Spring, no por auto-invocación dentro de la misma clase.
 */
@Service
@RequiredArgsConstructor
public class OfertaMarketingService {

    private final UsuarioRepository usuarioRepository;
    private final OfertaMarketingBatchSender ofertaMarketingBatchSender;

    public void enviarOferta(EnviarOfertaRequest request, String email) {
        Usuario usuarioAutenticado = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));

        if (usuarioAutenticado.getRol() != Role.ADMIN) {
            throw new AccessDeniedException("No está autorizado para enviar ofertas de marketing");
        }

        ofertaMarketingBatchSender.enviarEnLotes(request);
    }
}
