package com.ecommerce.backend.mapper;

import com.ecommerce.backend.dto.AdminProductoDetalleDto;
import com.ecommerce.backend.dto.AtributoValorDto;
import com.ecommerce.backend.dto.ImagenProductoDto;
import com.ecommerce.backend.dto.ProductoDetalleDto;
import com.ecommerce.backend.dto.ProductoResumenDto;
import com.ecommerce.backend.entity.ImagenProducto;
import com.ecommerce.backend.entity.Producto;
import com.ecommerce.backend.entity.ValorAtributoProducto;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ProductoMapper {

    public ProductoResumenDto toResumenDto(Producto p, String imagenPrincipalUrl, Integer stockDisponible) {
        if (p == null) {
            return null;
        }

        return ProductoResumenDto.builder()
                .id(p.getId())
                .sku(p.getSku())
                .nombre(p.getNombre())
                .slug(p.getSlug())
                .precioBase(p.getPrecioVenta())
                .activo(p.getActivo())
                .vendible(p.getVendible())
                .marca(p.getMarca() != null ? p.getMarca().getNombre() : null)
                .marcaSlug(p.getMarca() != null ? p.getMarca().getSlug() : null)
                .categoria(p.getCategoria() != null ? p.getCategoria().getNombre() : null)
                .categoriaSlug(p.getCategoria() != null ? p.getCategoria().getSlug() : null)
                .imagenPrincipal(imagenPrincipalUrl)
                .stockDisponible(stockDisponible != null ? stockDisponible : 0)
                .build();
    }

    public ProductoDetalleDto toDetalleDto(Producto p, List<ImagenProducto> imagenes, List<ValorAtributoProducto> atributos, Integer stockDisponible) {
        if (p == null) {
            return null;
        }

        List<ImagenProductoDto> imagenesDto = (imagenes != null) ? imagenes.stream().map(this::toImagenDto).toList() : List.of();
        List<AtributoValorDto> atributosDto = (atributos != null) ? atributos.stream().map(this::toAtributoDto).toList() : List.of();

        return ProductoDetalleDto.builder()
                .id(p.getId())
                .sku(p.getSku())
                .nombre(p.getNombre())
                .slug(p.getSlug())
                .descripcion(p.getDescripcion())
                .precioBase(p.getPrecioVenta())
                .activo(p.getActivo())
                .vendible(p.getVendible())
                .requiereSerie(p.getRequiereSerie())
                .requiereEnvio(p.getRequiereEnvio())
                .marca(p.getMarca() != null ? p.getMarca().getNombre() : null)
                .marcaSlug(p.getMarca() != null ? p.getMarca().getSlug() : null)
                .categoria(p.getCategoria() != null ? p.getCategoria().getNombre() : null)
                .categoriaSlug(p.getCategoria() != null ? p.getCategoria().getSlug() : null)
                .tipo(p.getTipo() != null ? p.getTipo().getCodigo() : null)
                .stockDisponible(stockDisponible != null ? stockDisponible : 0)
                .imagenes(imagenesDto)
                .atributos(atributosDto)
                .build();
    }

    public AdminProductoDetalleDto toAdminDetalleDto(Producto p, List<ImagenProducto> imagenes, List<ValorAtributoProducto> atributos, Integer stockDisponible) {
        if (p == null) {
            return null;
        }

        List<ImagenProductoDto> imagenesDto = (imagenes != null) ? imagenes.stream().map(this::toImagenDto).toList() : List.of();
        List<AtributoValorDto> atributosDto = (atributos != null) ? atributos.stream().map(this::toAtributoDto).toList() : List.of();

        return AdminProductoDetalleDto.builder()
                .id(p.getId())
                .sku(p.getSku())
                .nombre(p.getNombre())
                .slug(p.getSlug())
                .descripcion(p.getDescripcion())
                .precioBase(p.getPrecioBase())
                .costoReferencia(p.getCostoReferencia())
                .activo(p.getActivo())
                .vendible(p.getVendible())
                .requiereSerie(p.getRequiereSerie())
                .requiereEnvio(p.getRequiereEnvio())
                .marca(p.getMarca() != null ? p.getMarca().getNombre() : null)
                .marcaSlug(p.getMarca() != null ? p.getMarca().getSlug() : null)
                .categoria(p.getCategoria() != null ? p.getCategoria().getNombre() : null)
                .categoriaSlug(p.getCategoria() != null ? p.getCategoria().getSlug() : null)
                .tipo(p.getTipo() != null ? p.getTipo().getCodigo() : null)
                .stockDisponible(stockDisponible != null ? stockDisponible : 0)
                .imagenes(imagenesDto)
                .atributos(atributosDto)
                .build();
    }

    public ImagenProductoDto toImagenDto(ImagenProducto img) {
        if (img == null) {
            return null;
        }
        return ImagenProductoDto.builder()
                .id(img.getId())
                .url(img.getUrl())
                .textoAlternativo(img.getTextoAlternativo())
                .posicion(img.getPosicion())
                .principal(img.getPrincipal())
                .build();
    }

    public AtributoValorDto toAtributoDto(ValorAtributoProducto v) {
        if (v == null || v.getAtributo() == null) {
            return null;
        }

        String valorRepresentado = "";
        if (v.getValorTexto() != null) {
            valorRepresentado = v.getValorTexto();
        } else if (v.getValorNumero() != null) {
            valorRepresentado = v.getValorNumero().stripTrailingZeros().toPlainString();
        } else if (v.getValorBooleano() != null) {
            valorRepresentado = v.getValorBooleano() ? "Sí" : "No";
        }

        return AtributoValorDto.builder()
                .codigo(v.getAtributo().getCodigo())
                .nombre(v.getAtributo().getNombre())
                .tipoDato(v.getAtributo().getTipoDato())
                .unidad(v.getAtributo().getUnidad())
                .valor(valorRepresentado)
                .build();
    }
}
