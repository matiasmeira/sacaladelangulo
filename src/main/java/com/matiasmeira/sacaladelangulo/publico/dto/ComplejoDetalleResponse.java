package com.matiasmeira.sacaladelangulo.publico.dto;

import com.matiasmeira.sacaladelangulo.establecimiento.dto.FeedbackDestacadoDto;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.HorarioAtencionDto;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Servicio;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Detalle público de un complejo. No incluye duenoId ni ningún otro dato interno del
 * dueño (ver contrato de zona pública).
 */
public record ComplejoDetalleResponse(
        String slug,
        String nombre,
        String direccion,
        Double latitud,
        Double longitud,
        Set<Deporte> deportes,
        Set<Servicio> servicios,
        List<String> fotos,
        List<HorarioAtencionDto> horariosAtencion,
        List<CanchaPublicaDto> canchas,
        BigDecimal precioDesde,
        Boolean requiereSena,
        BigDecimal senaDesde,
        Double promedioCalificacion,
        Long cantidadCalificaciones,
        FeedbackDestacadoDto comentarioDestacado
) {
}
