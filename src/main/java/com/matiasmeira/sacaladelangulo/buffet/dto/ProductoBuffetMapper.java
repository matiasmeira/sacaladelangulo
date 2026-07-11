package com.matiasmeira.sacaladelangulo.buffet.dto;

import com.matiasmeira.sacaladelangulo.buffet.model.ProductoBuffet;
import org.springframework.stereotype.Component;

@Component
public class ProductoBuffetMapper {

    public ProductoBuffetResponse mapToResponse(ProductoBuffet producto) {
        return new ProductoBuffetResponse(
                producto.getId(),
                producto.getNombre(),
                producto.getDescripcion(),
                producto.getPrecio(),
                producto.getStock(),
                producto.getEstablecimiento().getId()
        );
    }
}
