package com.ecommerce.backend.repository;

import com.ecommerce.backend.entity.Producto;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductoRepository extends JpaRepository<Producto, UUID>, JpaSpecificationExecutor<Producto> {

    @Override
    @EntityGraph(attributePaths = {"categoria", "marca", "tipo"})
    Page<Producto> findAll(@Nullable Specification<Producto> spec, Pageable pageable);

    @Query("SELECT p FROM Producto p LEFT JOIN FETCH p.categoria LEFT JOIN FETCH p.marca LEFT JOIN FETCH p.tipo WHERE p.id = :id AND p.isDeleted = false")
    Optional<Producto> findConDetallesPorId(@Param("id") UUID id);

    @Query("SELECT p FROM Producto p LEFT JOIN FETCH p.categoria LEFT JOIN FETCH p.marca LEFT JOIN FETCH p.tipo WHERE p.slug = :slug AND p.isDeleted = false")
    Optional<Producto> findConDetallesPorSlug(@Param("slug") String slug);

    Optional<Producto> findByIdAndIsDeletedFalse(UUID id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Producto p WHERE p.id = :id AND p.isDeleted = false")
    Optional<Producto> findForGalleryUpdate(@Param("id") UUID id);

    Optional<Producto> findBySlugAndIsDeletedFalse(String slug);

    Optional<Producto> findBySkuAndIsDeletedFalse(String sku);

    boolean existsBySkuAndIsDeletedFalse(String sku);
    boolean existsBySku(String sku);
    boolean existsBySlug(String slug);
    boolean existsByMarca_Id(UUID marcaId);
    boolean existsByCategoria_Id(UUID categoriaId);

    boolean existsBySlugAndIsDeletedFalse(String slug);

    boolean existsBySkuAndIdNotAndIsDeletedFalse(String sku, UUID id);

    boolean existsBySlugAndIdNotAndIsDeletedFalse(String slug, UUID id);

    @Query("SELECT MIN(p.precioVenta) FROM Producto p WHERE p.activo = true AND p.vendible = true AND p.isDeleted = false")
    BigDecimal findMinPrecioActivo();

    @Query("SELECT MAX(p.precioVenta) FROM Producto p WHERE p.activo = true AND p.vendible = true AND p.isDeleted = false")
    BigDecimal findMaxPrecioActivo();

    @Query("SELECT p.categoria.id, COUNT(p) FROM Producto p WHERE p.activo = true AND p.vendible = true AND p.isDeleted = false GROUP BY p.categoria.id")
    List<Object[]> countProductosPorCategoria();

    @Query("SELECT p.marca.id, COUNT(p) FROM Producto p WHERE p.activo = true AND p.vendible = true AND p.isDeleted = false AND p.marca IS NOT NULL GROUP BY p.marca.id")
    List<Object[]> countProductosPorMarca();

    @Query("SELECT p.marca.id, COUNT(p) FROM Producto p WHERE p.categoria.id = :categoriaId AND p.activo = true AND p.vendible = true AND p.isDeleted = false AND p.marca IS NOT NULL GROUP BY p.marca.id")
    List<Object[]> countProductosPorMarcaYCategoria(@Param("categoriaId") UUID categoriaId);

    @Query("SELECT MIN(p.precioVenta) FROM Producto p WHERE p.categoria.id = :categoriaId AND p.activo = true AND p.vendible = true AND p.isDeleted = false")
    BigDecimal findMinPrecioActivoPorCategoria(@Param("categoriaId") UUID categoriaId);

    @Query("SELECT MAX(p.precioVenta) FROM Producto p WHERE p.categoria.id = :categoriaId AND p.activo = true AND p.vendible = true AND p.isDeleted = false")
    BigDecimal findMaxPrecioActivoPorCategoria(@Param("categoriaId") UUID categoriaId);

    @Query("SELECT p FROM Producto p LEFT JOIN FETCH p.categoria LEFT JOIN FETCH p.marca WHERE p.categoria.id = :categoriaId AND p.activo = true AND p.vendible = true AND p.isDeleted = false")
    List<Producto> findActivosPorCategoriaId(@Param("categoriaId") UUID categoriaId);

    @Query("SELECT p FROM Producto p LEFT JOIN FETCH p.categoria LEFT JOIN FETCH p.marca WHERE LOWER(p.categoria.slug) = LOWER(:slug) AND p.activo = true AND p.vendible = true AND p.isDeleted = false")
    List<Producto> findActivosPorCategoriaSlug(@Param("slug") String slug);

    @Query("SELECT p FROM Producto p LEFT JOIN FETCH p.categoria LEFT JOIN FETCH p.marca WHERE LOWER(p.categoria.acronimo) = LOWER(:acronimo) AND p.activo = true AND p.vendible = true AND p.isDeleted = false")
    List<Producto> findActivosPorCategoriaAcronimo(@Param("acronimo") String acronimo);
}
