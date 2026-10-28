package com.ecommerce.backend.service;

import com.ecommerce.backend.dto.OfertaDto;
import com.ecommerce.backend.dto.OfertasResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OfertaService {
    private final NamedParameterJdbcTemplate jdbc;

    private static final String BASE = """
        WITH precios AS (
          SELECT p.id AS producto_id,p.nombre,p.slug,m.nombre AS marca,m.slug AS marca_slug,
            c.nombre AS categoria,c.slug AS categoria_slug,
            CASE WHEN o.descuento_porcentaje>0 THEN p.precio_base ELSE pp.precio_anterior END AS precio_anterior,
            GREATEST(0.01,ROUND(p.precio_base*(100-COALESCE(o.descuento_porcentaje,0))/100,2)) AS precio_actual,
            o.nombre AS promocion,o.inicio_at,o.fin_at,o.id AS promocion_id,
            COALESCE((SELECT SUM(e.cantidad-e.reservado) FROM rya.existencias e WHERE e.producto_id=p.id AND e.is_deleted=false),0) AS stock,
            (SELECT i.url FROM rya.imagenes_producto i WHERE i.producto_id=p.id AND i.is_deleted=false ORDER BY i.principal DESC,i.posicion ASC,i.id LIMIT 1) AS imagen
          FROM rya.promociones_productos pp JOIN rya.promociones o ON o.id=pp.promocion_id
          JOIN rya.productos p ON p.id=pp.producto_id JOIN rya.categorias c ON c.id=p.categoria_id LEFT JOIN rya.marcas m ON m.id=p.marca_id
          WHERE pp.is_deleted=false AND o.is_deleted=false AND o.activa=true
            AND (o.inicio_at IS NULL OR o.inicio_at<=:ahora) AND (o.fin_at IS NULL OR o.fin_at>:ahora)
            AND p.is_deleted=false AND p.activo=true AND p.vendible=true
        ), ofertas AS (
          SELECT precios.*,ROUND((precio_anterior-precio_actual)*100/precio_anterior,2) AS descuento,
            precio_anterior-precio_actual AS ahorro,
            ROW_NUMBER() OVER(PARTITION BY producto_id ORDER BY precio_actual,fin_at ASC NULLS LAST,promocion_id) AS prioridad
          FROM precios WHERE precio_anterior>precio_actual AND precio_anterior>0
        ), vigentes AS (SELECT * FROM ofertas WHERE prioridad=1)
        """;

    public OfertasResponseDto listar(String q, String categoria, String marca, boolean enStock,
            BigDecimal descuentoMin, String orden, int page, int size) {
        var params = new MapSqlParameterSource().addValue("ahora", OffsetDateTime.now())
                .addValue("limite", size).addValue("offset", (long) page * size);
        var where = new StringBuilder(" WHERE 1=1");
        if (q != null && !q.isBlank()) {
            where.append(" AND LOWER(nombre) LIKE :q ESCAPE '!' ");
            params.addValue("q", "%" + q.trim().toLowerCase(java.util.Locale.ROOT)
                    .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        if (categoria != null && !categoria.isBlank()) {
            where.append(" AND categoria_slug=:categoria"); params.addValue("categoria", categoria);
        }
        if (marca != null && !marca.isBlank()) {
            where.append(" AND marca_slug=:marca"); params.addValue("marca", marca);
        }
        if (enStock) where.append(" AND stock>0");
        if (descuentoMin != null) {
            where.append(" AND descuento>=:minimo"); params.addValue("minimo", descuentoMin);
        }
        String order = Map.of("descuento", "descuento DESC", "precio-asc", "precio_actual ASC",
                "precio-desc", "precio_actual DESC", "nombre", "nombre ASC",
                "fin", "fin_at ASC NULLS LAST").getOrDefault(orden, "descuento DESC");
        long total = jdbc.queryForObject(BASE + "SELECT COUNT(*) FROM vigentes" + where, params, Long.class);
        var items = jdbc.query(BASE + "SELECT * FROM vigentes" + where
                + " ORDER BY " + order + ", producto_id LIMIT :limite OFFSET :offset", params,
                (rs, row) -> new OfertaDto(rs.getObject("producto_id", UUID.class), rs.getString("nombre"),
                    rs.getString("slug"), rs.getString("marca"), rs.getString("categoria"), rs.getString("imagen"),
                    rs.getInt("stock"), rs.getBigDecimal("precio_anterior"), rs.getBigDecimal("precio_actual"),
                    rs.getBigDecimal("descuento"), rs.getBigDecimal("ahorro"), rs.getString("promocion"),
                    rs.getObject("inicio_at", OffsetDateTime.class), rs.getObject("fin_at", OffsetDateTime.class)));
        var categorias = jdbc.query(BASE + "SELECT DISTINCT categoria_slug, categoria FROM vigentes ORDER BY categoria", params,
                (rs, row) -> new OfertasResponseDto.Filtro(rs.getString(1), rs.getString(2)));
        var marcas = jdbc.query(BASE + "SELECT DISTINCT marca_slug, marca FROM vigentes WHERE marca_slug IS NOT NULL ORDER BY marca", params,
                (rs, row) -> new OfertasResponseDto.Filtro(rs.getString(1), rs.getString(2)));
        return new OfertasResponseDto(items, page, size, total, (int) Math.ceil((double) total / size), categorias, marcas);
    }
}
