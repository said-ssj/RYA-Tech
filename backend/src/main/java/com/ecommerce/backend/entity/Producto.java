package com.ecommerce.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "productos", schema = "rya")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Producto {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "categoria_id", nullable = false)
    private Categoria categoria;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "marca_id")
    private Marca marca;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tipo_id", nullable = false)
    private TipoProducto tipo;

    @Column(name = "sku", nullable = false, unique = true, length = 80)
    private String sku;

    @Column(name = "nombre", nullable = false, length = 220)
    private String nombre;

    @Column(name = "slug", nullable = false, unique = true, length = 240)
    private String slug;

    @Column(name = "descripcion", columnDefinition = "text")
    private String descripcion;

    @Column(name = "precio_base", nullable = false, precision = 14, scale = 2)
    private BigDecimal precioBase;

    @org.hibernate.annotations.Formula("(select greatest(0.01,round(precio_base * (100 - coalesce(max(promo.descuento_porcentaje),0)) / 100,2)) from rya.promociones_productos vinculo join rya.promociones promo on promo.id=vinculo.promocion_id where vinculo.producto_id=id and vinculo.is_deleted=false and promo.is_deleted=false and promo.activa=true and (promo.inicio_at is null or promo.inicio_at<=CURRENT_TIMESTAMP) and (promo.fin_at is null or promo.fin_at>CURRENT_TIMESTAMP))")
    private BigDecimal precioVenta;

    public BigDecimal getPrecioVenta(){return precioVenta!=null?precioVenta:precioBase;}

    @Column(name = "costo_referencia", precision = 14, scale = 2)
    private BigDecimal costoReferencia;

    @Column(name = "activo", nullable = false)
    @Builder.Default
    private Boolean activo = true;

    @Column(name = "vendible", nullable = false)
    @Builder.Default
    private Boolean vendible = true;

    @Column(name = "requiere_serie", nullable = false)
    @Builder.Default
    private Boolean requiereSerie = false;

    @Column(name = "requiere_envio", nullable = false)
    @Builder.Default
    private Boolean requiereEnvio = true;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private Boolean isDeleted = false;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "producto", fetch = FetchType.LAZY)
    @OrderBy("posicion ASC")
    @Builder.Default
    private List<ImagenProducto> imagenes = new ArrayList<>();

    @OneToMany(mappedBy = "producto", fetch = FetchType.LAZY)
    @Builder.Default
    private List<ValorAtributoProducto> valoresAtributos = new ArrayList<>();

    @OneToMany(mappedBy = "producto", fetch = FetchType.LAZY)
    @Builder.Default
    private List<Existencia> existencias = new ArrayList<>();
}
