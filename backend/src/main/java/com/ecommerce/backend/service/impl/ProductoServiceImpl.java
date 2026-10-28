package com.ecommerce.backend.service.impl;

import com.ecommerce.backend.dto.AdminProductoDetalleDto;
import com.ecommerce.backend.dto.PaginatedResponseDto;
import com.ecommerce.backend.dto.ProductoDetalleDto;
import com.ecommerce.backend.dto.ProductoResumenDto;
import com.ecommerce.backend.entity.ImagenProducto;
import com.ecommerce.backend.entity.Existencia;
import com.ecommerce.backend.entity.Producto;
import com.ecommerce.backend.entity.ValorAtributoProducto;
import com.ecommerce.backend.exception.BadRequestException;
import com.ecommerce.backend.exception.ResourceNotFoundException;
import com.ecommerce.backend.mapper.ProductoMapper;
import com.ecommerce.backend.repository.ExistenciaRepository;
import com.ecommerce.backend.repository.ImagenProductoRepository;
import com.ecommerce.backend.repository.ProductoRepository;
import com.ecommerce.backend.repository.AtributoTecnicoRepository;
import com.ecommerce.backend.repository.ValorAtributoProductoRepository;
import com.ecommerce.backend.service.ProductoService;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductoServiceImpl implements ProductoService {

    private final ProductoRepository productoRepository;
    private final ImagenProductoRepository imagenProductoRepository;
    private final ValorAtributoProductoRepository valorAtributoProductoRepository;
    private final ExistenciaRepository existenciaRepository;
    private final AtributoTecnicoRepository atributoTecnicoRepository;
    private final com.ecommerce.backend.repository.CategoriaRepository categoriaRepository;
    private final com.ecommerce.backend.repository.MarcaRepository marcaRepository;
    private final com.ecommerce.backend.repository.TipoProductoRepository tipoProductoRepository;
    private final ProductoMapper productoMapper;
    private final com.ecommerce.backend.service.LegacyProductTechnicalReader technicalReader;
    private final com.ecommerce.backend.service.CatalogAttributeRestorer attributeRestorer;

    @Override
    @Transactional
    public AdminProductoDetalleDto agregarImagenesProducto(UUID id, String sku, List<com.ecommerce.backend.dto.ImagenProductoDto> incoming) {
        var product=productoRepository.findForGalleryUpdate(id).orElseThrow(()->new ResourceNotFoundException("Producto no encontrado"));
        if(sku==null||!product.getSku().equals(sku))throw new BadRequestException("El SKU exacto no coincide. No se agregó ninguna imagen.");
        if(incoming==null||incoming.isEmpty())throw new BadRequestException("Incluye al menos una imagen.");
        validarGaleriaImagenes(incoming);
        var existing=imagenProductoRepository.findByProductoIdAndIsDeletedFalseOrderByPosicionAsc(id);
        var known=existing.stream().map(image->image.getUrl().trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        var additions=incoming.stream().filter(image->!known.contains(image.getUrl().trim().toLowerCase(Locale.ROOT))).toList();
        if(existing.size()+additions.size()>8)throw new BadRequestException("La galería resultante supera 8 imágenes. No se agregó ni eliminó ninguna; selecciona menos enlaces.");
        int position=existing.stream().map(ImagenProducto::getPosicion).filter(Objects::nonNull).max(Integer::compareTo).orElse(-1)+1;
        boolean first=existing.isEmpty();
        for(var image:additions){
            imagenProductoRepository.save(ImagenProducto.builder().producto(product).url(image.getUrl().trim())
                .textoAlternativo(image.getTextoAlternativo()==null||image.getTextoAlternativo().isBlank()?product.getNombre():image.getTextoAlternativo().trim())
                .posicion(position++).principal(first).isDeleted(false).build());
            first=false;
        }
        return construirAdminDetalleDto(product);
    }

    @Override
    @Transactional
    public AdminProductoDetalleDto actualizarFichaProducto(UUID id, String sku, com.ecommerce.backend.dto.FichaProductoDto ficha) {
        var product=productoRepository.findByIdAndIsDeletedFalse(id).orElseThrow(()->new ResourceNotFoundException("Producto no encontrado"));
        if(sku==null||!product.getSku().equals(sku)||ficha==null)throw new BadRequestException("Confirma el SKU exacto y una ficha técnica válida. No se modificó el producto.");
        new com.ecommerce.backend.service.ProductTechnicalData(atributoTecnicoRepository,valorAtributoProductoRepository,categoriaRepository,attributeRestorer::restore).save(product,ficha);
        return construirAdminDetalleDto(product);
    }

    @Override
    @Transactional
    public AdminProductoDetalleDto actualizarCamposFichaProducto(UUID id,String sku,com.ecommerce.backend.dto.FichaProductoDto ficha,List<String> campos) {
        var product=productoRepository.findForGalleryUpdate(id).orElseThrow(()->new ResourceNotFoundException("Producto no encontrado"));
        if(sku==null||!product.getSku().equals(sku)||ficha==null||ficha.valores()==null||campos==null||campos.isEmpty()
            ||new HashSet<>(campos).size()!=campos.size()||!new HashSet<>(campos).equals(ficha.valores().keySet()))
            throw new BadRequestException("Confirma el SKU exacto y los campos de ficha que se actualizarán; cada campo debe incluir su valor explícito.");
        var schema=com.ecommerce.backend.service.ProductTechnicalData.schema(product.getCategoria(),categoriaRepository);
        if(schema==null||!schema.tipo().equals(ficha.tipo())||campos.stream().anyMatch(key->schema.campos().stream().noneMatch(field->field.key().equals(key))))
            throw new BadRequestException("Los campos no corresponden al esquema técnico del producto actual.");
        var current=construirAdminDetalleDto(product).getFichaTecnica();
        var merged=new LinkedHashMap<String,Object>();
        if(current!=null&&current.valores()!=null)merged.putAll(current.valores());
        ficha.valores().forEach(merged::put);
        var patch=new com.ecommerce.backend.dto.FichaProductoDto(ficha.tipo(),current==null?null:current.fuenteUrl(),merged);
        new com.ecommerce.backend.service.ProductTechnicalData(atributoTecnicoRepository,valorAtributoProductoRepository,categoriaRepository,attributeRestorer::restore).save(product,patch);
        return construirAdminDetalleDto(product);
    }

    @Override
    public PaginatedResponseDto<ProductoResumenDto> listarProductos(
            String busqueda,
            UUID categoriaId,
            String categoriaSlug,
            UUID marcaId,
            String marcaSlug,
            BigDecimal precioMin,
            BigDecimal precioMax,
            Boolean soloEnStock,
            Map<String, List<String>> filtrosAtributos,
            int page,
            int size,
            String sortBy,
            String sortDir
    ) {
        if (precioMin != null && precioMin.signum() < 0) {
            throw new BadRequestException("El precio mínimo no puede ser negativo");
        }
        if (precioMax != null && precioMax.signum() < 0) {
            throw new BadRequestException("El precio máximo no puede ser negativo");
        }
        if (precioMin != null && precioMax != null && precioMin.compareTo(precioMax) > 0) {
            throw new BadRequestException("El precio mínimo no puede ser mayor que el precio máximo");
        }
        List<FiltroTecnico> filtrosTecnicos = normalizarFiltrosAtributos(filtrosAtributos);

        Specification<Producto> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Solo productos activos, vendibles y no eliminados
            predicates.add(cb.isFalse(root.get("isDeleted")));
            predicates.add(cb.isTrue(root.get("activo")));
            predicates.add(cb.isTrue(root.get("vendible")));

            // Búsqueda textual por nombre, SKU o descripción
            if (busqueda != null && !busqueda.trim().isEmpty()) {
                String term = "%" + busqueda.trim().toLowerCase() + "%";
                Predicate porNombre = cb.like(cb.lower(root.get("nombre")), term);
                Predicate porSku = cb.like(cb.lower(root.get("sku")), term);
                Predicate porDesc = cb.like(cb.lower(root.get("descripcion")), term);
                predicates.add(cb.or(porNombre, porSku, porDesc));
            }

            // Filtro por categoría (ID o slug)
            if (categoriaId != null) {
                predicates.add(cb.equal(root.get("categoria").get("id"), categoriaId));
            } else if (categoriaSlug != null && !categoriaSlug.trim().isEmpty()) {
                predicates.add(cb.equal(cb.lower(root.get("categoria").get("slug")), categoriaSlug.trim().toLowerCase()));
            }

            // Filtro por marca (ID o slug)
            if (marcaId != null) {
                predicates.add(cb.equal(root.get("marca").get("id"), marcaId));
            } else if (marcaSlug != null && !marcaSlug.trim().isEmpty()) {
                predicates.add(cb.equal(cb.lower(root.get("marca").get("slug")), marcaSlug.trim().toLowerCase()));
            }

            // Filtro por rango de precio
            if (precioMin != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("precioVenta"), precioMin));
            }
            if (precioMax != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("precioVenta"), precioMax));
            }

            // El filtro de stock forma parte de la consulta para conservar una paginación correcta.
            boolean ordenarDisponibilidad = "disponibilidad".equalsIgnoreCase(sortBy)
                    && !Long.class.equals(query.getResultType());
            if (Boolean.TRUE.equals(soloEnStock) || ordenarDisponibilidad) {
                Subquery<Long> stockDisponible = query.subquery(Long.class);
                Root<Existencia> existencia = stockDisponible.from(Existencia.class);
                stockDisponible.select(cb.sumAsLong(cb.diff(existencia.<Integer>get("cantidad"), existencia.<Integer>get("reservado"))));
                stockDisponible.where(
                        cb.equal(existencia.get("producto"), root),
                        cb.isFalse(existencia.get("isDeleted"))
                );
                var hayStock = cb.greaterThan(cb.coalesce(stockDisponible, 0L), 0L);
                if (Boolean.TRUE.equals(soloEnStock)) predicates.add(hayStock);
                if (ordenarDisponibilidad) {
                    var disponible = cb.<Integer>selectCase().when(hayStock, 1).otherwise(0);
                    query.orderBy("asc".equalsIgnoreCase(sortDir) ? cb.asc(disponible) : cb.desc(disponible),
                            cb.asc(root.get("nombre")), cb.asc(root.get("id")));
                }
            }

            // Cada atributo seleccionado debe coincidir; varios valores del mismo atributo se combinan con OR.
            for (FiltroTecnico filtro : filtrosTecnicos) {
                Subquery<Integer> atributoCoincidente = query.subquery(Integer.class);
                Root<ValorAtributoProducto> valor = atributoCoincidente.from(ValorAtributoProducto.class);
                List<Predicate> coincidenciasValor = new ArrayList<>();
                for (String opcion : filtro.valores()) {
                    if ("NUMERO".equals(filtro.tipoDato())) {
                        coincidenciasValor.add(cb.equal(valor.get("valorNumero"), new BigDecimal(opcion)));
                    } else if ("BOOLEANO".equals(filtro.tipoDato())) {
                        coincidenciasValor.add(cb.equal(valor.get("valorBooleano"), Boolean.valueOf(opcion)));
                    } else {
                        coincidenciasValor.add(cb.equal(
                                cb.lower(valor.get("valorTexto")), opcion.toLowerCase(Locale.ROOT)));
                    }
                }
                atributoCoincidente.select(cb.literal(1));
                atributoCoincidente.where(
                        cb.equal(valor.get("producto"), root),
                        cb.isFalse(valor.get("isDeleted")),
                        cb.equal(cb.lower(valor.get("atributo").get("codigo")), filtro.codigo().toLowerCase(Locale.ROOT)),
                        cb.or(coincidenciasValor.toArray(new Predicate[0]))
                );
                predicates.add(cb.exists(atributoCoincidente));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        // Ordenamiento seguro
        String campoOrden = "nombre";
        if ("precio".equalsIgnoreCase(sortBy) || "precioBase".equalsIgnoreCase(sortBy)) {
            campoOrden = "precioVenta";
        } else if ("created".equalsIgnoreCase(sortBy) || "createdAt".equalsIgnoreCase(sortBy)) {
            campoOrden = "createdAt";
        }
        Sort sort = "desc".equalsIgnoreCase(sortDir) ? Sort.by(campoOrden).descending() : Sort.by(campoOrden).ascending();
        sort = sort.and(Sort.by("id"));
        // La disponibilidad se ordena en SQL antes del LIMIT/OFFSET, mediante el stock agregado.
        if ("disponibilidad".equalsIgnoreCase(sortBy)) sort = Sort.unsorted();
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)), sort);

        Page<Producto> pageResult = productoRepository.findAll(spec, pageable);
        List<Producto> productos = pageResult.getContent();

        if (productos.isEmpty()) {
            return PaginatedResponseDto.<ProductoResumenDto>builder()
                    .content(List.of())
                    .page(pageResult.getNumber())
                    .size(pageResult.getSize())
                    .totalElements(pageResult.getTotalElements())
                    .totalPages(pageResult.getTotalPages())
                    .first(pageResult.isFirst())
                    .last(pageResult.isLast())
                    .build();
        }

        List<UUID> productIds = productos.stream().map(Producto::getId).toList();

        // Evitar N+1: Batch load de imágenes principales
        List<ImagenProducto> imagenes = imagenProductoRepository.findByProductoIdInAndIsDeletedFalseOrderByPosicionAsc(productIds);
        Map<UUID, String> imagenMap = new HashMap<>();
        for (ImagenProducto img : imagenes) {
            UUID pId = img.getProducto().getId();
            if (!imagenMap.containsKey(pId) || Boolean.TRUE.equals(img.getPrincipal())) {
                imagenMap.put(pId, img.getUrl());
            }
        }

        // Evitar N+1: Batch load de stock disponible
        List<Object[]> stockRows = existenciaRepository.findStockDisponiblePorProductoIds(productIds);
        Map<UUID, Integer> stockMap = new HashMap<>();
        for (Object[] row : stockRows) {
            UUID pId = (UUID) row[0];
            Long stockTotal = (Long) row[1];
            stockMap.put(pId, stockTotal != null ? stockTotal.intValue() : 0);
        }

        List<ProductoResumenDto> dtos = productos.stream()
                .map(p -> {
                    String imgUrl = imagenMap.get(p.getId());
                    int stock = stockMap.getOrDefault(p.getId(), 0);
                    return productoMapper.toResumenDto(p, imgUrl, stock);
                })
                .toList();

        return PaginatedResponseDto.<ProductoResumenDto>builder()
                .content(dtos)
                .page(pageResult.getNumber())
                .size(pageResult.getSize())
                .totalElements(pageResult.getTotalElements())
                .totalPages(pageResult.getTotalPages())
                .first(pageResult.isFirst())
                .last(pageResult.isLast())
                .build();
    }

    @Override
    public ProductoDetalleDto obtenerPorId(UUID id) {
        Producto producto = productoRepository.findConDetallesPorId(id)
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado con ID: " + id));

        return construirDetalleDto(producto);
    }

    @Override
    public ProductoDetalleDto obtenerPorSlug(String slug) {
        Producto producto = productoRepository.findConDetallesPorSlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado con slug: " + slug));

        return construirDetalleDto(producto);
    }

    @Override
    public AdminProductoDetalleDto obtenerPorIdAdmin(UUID id) {
        Producto producto = productoRepository.findConDetallesPorId(id)
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado con ID: " + id));

        return construirAdminDetalleDto(producto);
    }

    private ProductoDetalleDto construirDetalleDto(Producto producto) {
        List<ImagenProducto> imagenes = imagenProductoRepository.findByProductoIdAndIsDeletedFalseOrderByPosicionAsc(producto.getId());
        List<ValorAtributoProducto> atributos = valorAtributoProductoRepository.findConAtributoPorProductoId(producto.getId());
        Integer stock = existenciaRepository.findStockDisponiblePorProductoId(producto.getId());

        var publicAttributes=atributos.stream().filter(value->value.getAtributo()==null || !com.ecommerce.backend.service.ProductTechnicalData.isInternalAttributeCode(value.getAtributo().getCodigo())).toList();
        return productoMapper.toDetalleDto(producto, imagenes, publicAttributes, stock);
    }

    private AdminProductoDetalleDto construirAdminDetalleDto(Producto producto) {
        List<ImagenProducto> imagenes = imagenProductoRepository.findByProductoIdAndIsDeletedFalseOrderByPosicionAsc(producto.getId());
        List<ValorAtributoProducto> atributos = valorAtributoProductoRepository.findConAtributoPorProductoId(producto.getId());
        Integer stock = existenciaRepository.findStockDisponiblePorProductoId(producto.getId());

        var detalle = productoMapper.toAdminDetalleDto(producto, imagenes, atributos, stock);
        detalle.setFichaTecnica(technicalReader.read(producto, atributos));
        return detalle;
    }

    private List<FiltroTecnico> normalizarFiltrosAtributos(Map<String, List<String>> filtros) {
        if (filtros == null || filtros.isEmpty()) {
            return List.of();
        }
        if (filtros.size() > 20) {
            throw new BadRequestException("No se pueden aplicar más de 20 filtros técnicos a la vez");
        }

        List<FiltroTecnico> resultado = new ArrayList<>();
        filtros.forEach((codigo, valores) -> {
            String codigoLimpio = codigo == null ? "" : codigo.trim();
            if (!codigoLimpio.matches("[A-Za-z0-9_-]{1,90}")) {
                throw new BadRequestException("Código de filtro técnico inválido");
            }
            List<String> opciones = Optional.ofNullable(valores).orElse(List.of()).stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(valor -> !valor.isEmpty())
                    .distinct()
                    .limit(20)
                    .toList();
            if (!opciones.isEmpty()) {
                var atributo = atributoTecnicoRepository.findByCodigoAndIsDeletedFalse(codigoLimpio)
                        .filter(item -> item.getCodigo().startsWith("CATALOG_TECH_")
                            ? com.ecommerce.backend.service.ProductTechnicalData.isPublicFilterCode(item.getCodigo())
                            : Boolean.TRUE.equals(item.getFiltrable()))
                        .orElseThrow(() -> new BadRequestException("Filtro técnico desconocido o no filtrable: " + codigoLimpio));
                String tipoDato = Optional.ofNullable(atributo.getTipoDato()).orElse("TEXTO").toUpperCase(Locale.ROOT);
                if ("NUMERO".equals(tipoDato)) {
                    try {
                        opciones.forEach(BigDecimal::new);
                    } catch (NumberFormatException ex) {
                        throw new BadRequestException("Valor numérico inválido para el filtro: " + codigoLimpio);
                    }
                } else if ("BOOLEANO".equals(tipoDato)
                        && opciones.stream().anyMatch(valor -> !"true".equalsIgnoreCase(valor) && !"false".equalsIgnoreCase(valor))) {
                    throw new BadRequestException("Valor booleano inválido para el filtro: " + codigoLimpio);
                }
                resultado.add(new FiltroTecnico(codigoLimpio, tipoDato, opciones));
            }
        });
        return List.copyOf(resultado);
    }

    private record FiltroTecnico(String codigo, String tipoDato, List<String> valores) {}

    @Override
    public PaginatedResponseDto<ProductoResumenDto> listarProductosAdmin(
            String busqueda,
            UUID categoriaId,
            UUID marcaId,
            String estado,
            int page,
            int size,
            String sortBy,
            String sortDir
    ) {
        Specification<Producto> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            predicates.add(cb.isFalse(root.get("isDeleted")));

            if (busqueda != null && !busqueda.trim().isEmpty()) {
                String term = "%" + busqueda.trim().toLowerCase(Locale.ROOT) + "%";
                Predicate porNombre = cb.like(cb.lower(root.get("nombre")), term);
                Predicate porSku = cb.like(cb.lower(root.get("sku")), term);
                Predicate porDesc = cb.like(cb.lower(root.get("descripcion")), term);
                predicates.add(cb.or(porNombre, porSku, porDesc));
            }

            if (categoriaId != null) {
                predicates.add(cb.equal(root.get("categoria").get("id"), categoriaId));
            }

            if (marcaId != null) {
                predicates.add(cb.equal(root.get("marca").get("id"), marcaId));
            }

            if (estado != null && !estado.trim().isEmpty()) {
                String st = estado.trim().toLowerCase(Locale.ROOT);
                if ("active".equals(st) || "publicado".equals(st) || "publicados".equals(st)) {
                    predicates.add(cb.isTrue(root.get("activo")));
                    predicates.add(cb.isTrue(root.get("vendible")));
                } else if ("draft".equals(st) || "borrador".equals(st) || "borradores".equals(st)) {
                    predicates.add(cb.or(cb.isFalse(root.get("activo")), cb.isFalse(root.get("vendible"))));
                } else if ("inactive".equals(st) || "desactivado".equals(st) || "desactivados".equals(st)) {
                    predicates.add(cb.isFalse(root.get("activo")));
                } else if ("alert".equals(st) || "alerta".equals(st) || "alertas".equals(st) || "bajo_stock".equals(st)) {
                    Subquery<Long> stockSubquery = query.subquery(Long.class);
                    Root<Existencia> ex = stockSubquery.from(Existencia.class);
                    stockSubquery.select(cb.sumAsLong(cb.diff(ex.<Integer>get("cantidad"), ex.<Integer>get("reservado"))));
                    stockSubquery.where(
                            cb.equal(ex.get("producto"), root),
                            cb.isFalse(ex.get("isDeleted"))
                    );
                    predicates.add(cb.lessThanOrEqualTo(cb.coalesce(stockSubquery, 0L), 3L));
                }
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        String campoOrden = "nombre";
        if ("precio".equalsIgnoreCase(sortBy) || "precioBase".equalsIgnoreCase(sortBy)) {
            campoOrden = "precioVenta";
        } else if ("created".equalsIgnoreCase(sortBy) || "createdAt".equalsIgnoreCase(sortBy)) {
            campoOrden = "createdAt";
        } else if ("updated".equalsIgnoreCase(sortBy) || "updatedAt".equalsIgnoreCase(sortBy)) {
            campoOrden = "updatedAt";
        } else if ("sku".equalsIgnoreCase(sortBy)) {
            campoOrden = "sku";
        }
        Sort sort = "desc".equalsIgnoreCase(sortDir) ? Sort.by(campoOrden).descending() : Sort.by(campoOrden).ascending();
        sort = sort.and(Sort.by("id"));

        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)), sort);
        Page<Producto> pageResult = productoRepository.findAll(spec, pageable);
        List<Producto> productos = pageResult.getContent();

        if (productos.isEmpty()) {
            return PaginatedResponseDto.<ProductoResumenDto>builder()
                    .content(List.of())
                    .page(pageResult.getNumber())
                    .size(pageResult.getSize())
                    .totalElements(pageResult.getTotalElements())
                    .totalPages(pageResult.getTotalPages())
                    .first(pageResult.isFirst())
                    .last(pageResult.isLast())
                    .build();
        }

        List<UUID> productIds = productos.stream().map(Producto::getId).toList();

        List<ImagenProducto> imagenes = imagenProductoRepository.findByProductoIdInAndIsDeletedFalseOrderByPosicionAsc(productIds);
        Map<UUID, String> imagenMap = new HashMap<>();
        for (ImagenProducto img : imagenes) {
            UUID pId = img.getProducto().getId();
            if (!imagenMap.containsKey(pId) || Boolean.TRUE.equals(img.getPrincipal())) {
                imagenMap.put(pId, img.getUrl());
            }
        }

        List<Object[]> stockRows = existenciaRepository.findStockDisponiblePorProductoIds(productIds);
        Map<UUID, Integer> stockMap = new HashMap<>();
        for (Object[] row : stockRows) {
            UUID pId = (UUID) row[0];
            Long stockTotal = (Long) row[1];
            stockMap.put(pId, stockTotal != null ? stockTotal.intValue() : 0);
        }

        List<ProductoResumenDto> dtos = productos.stream()
                .map(p -> {
                    String imgUrl = imagenMap.get(p.getId());
                    int stock = stockMap.getOrDefault(p.getId(), 0);
                    return productoMapper.toResumenDto(p, imgUrl, stock);
                })
                .toList();

        return PaginatedResponseDto.<ProductoResumenDto>builder()
                .content(dtos)
                .page(pageResult.getNumber())
                .size(pageResult.getSize())
                .totalElements(pageResult.getTotalElements())
                .totalPages(pageResult.getTotalPages())
                .first(pageResult.isFirst())
                .last(pageResult.isLast())
                .build();
    }

    @Override
    @Transactional
    public AdminProductoDetalleDto crearProducto(com.ecommerce.backend.dto.AdminProductoDto dto) {
        validarGaleriaImagenes(dto.getImagenes());
        validarUrlHttps(dto.getImagenPrincipalUrl());

        if (dto.getNombre() == null || dto.getNombre().isBlank()) {
            throw new BadRequestException("El nombre del producto es obligatorio");
        }
        if (dto.getSku() == null || dto.getSku().isBlank()) {
            throw new BadRequestException("El SKU es obligatorio");
        }
        String sku = dto.getSku().trim();
        if (productoRepository.existsBySkuAndIsDeletedFalse(sku)) {
            throw new BadRequestException("Ya existe un producto con el SKU: " + sku);
        }

        if (productoRepository.existsBySku(sku)) {
            throw new com.ecommerce.backend.exception.ConflictException("Ese SKU pertenece a un producto eliminado. Revisa su historial y solicita una restauración auditada; no se creará ni reactivará desde la importación.");
        }
        String slug = dto.getSlug();
        if (slug == null || slug.isBlank()) {
            slug = dto.getNombre().toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("^-+|-+$", "");
        } else {
            slug = slug.toLowerCase(Locale.ROOT).trim();
        }
        if (slug.isBlank()) {
            slug = "prod-" + UUID.randomUUID().toString().substring(0, 8);
        }
        if (productoRepository.existsBySlugAndIsDeletedFalse(slug)) {
            slug = slug + "-" + UUID.randomUUID().toString().substring(0, 6);
        }
        if (productoRepository.existsBySlug(slug)) {
            throw new com.ecommerce.backend.exception.ConflictException("El enlace (slug) pertenece a un producto eliminado. Revisa su historial antes de crear otro producto.");
        }

        com.ecommerce.backend.entity.Categoria categoria = null;
        if (dto.getCategoriaId() != null) {
            categoria = categoriaRepository.findById(dto.getCategoriaId())
                    .filter(c -> !Boolean.TRUE.equals(c.getIsDeleted()))
                    .orElse(null);
        }
        if (categoria == null && dto.getCategoriaNombre() != null && !dto.getCategoriaNombre().isBlank()) {
            String slugCat = dto.getCategoriaNombre().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
            categoria = categoriaRepository.findBySlugAndIsDeletedFalse(slugCat)
                    .or(() -> categoriaRepository.findAll().stream().filter(c -> !Boolean.TRUE.equals(c.getIsDeleted()) && c.getNombre().equalsIgnoreCase(dto.getCategoriaNombre().trim())).findFirst())
                    .orElse(null);
        }
        if (categoria == null) {
            categoria = categoriaRepository.findAll().stream().filter(c -> !Boolean.TRUE.equals(c.getIsDeleted())).findFirst()
                    .orElseThrow(() -> new BadRequestException("La categoría es obligatoria"));
        }

        com.ecommerce.backend.entity.Marca marca = null;
        if (dto.getMarcaId() != null) {
            marca = marcaRepository.findById(dto.getMarcaId())
                    .filter(m -> !Boolean.TRUE.equals(m.getIsDeleted()))
                    .orElse(null);
        }
        if (marca == null && dto.getMarcaNombre() != null && !dto.getMarcaNombre().isBlank()) {
            String slugMarca = dto.getMarcaNombre().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
            marca = marcaRepository.findBySlugAndIsDeletedFalse(slugMarca)
                    .or(() -> marcaRepository.findAll().stream().filter(m -> !Boolean.TRUE.equals(m.getIsDeleted()) && m.getNombre().equalsIgnoreCase(dto.getMarcaNombre().trim())).findFirst())
                    .orElseGet(() -> marcaRepository.save(com.ecommerce.backend.entity.Marca.builder()
                            .nombre(dto.getMarcaNombre().trim())
                            .slug(slugMarca)
                            .build()));
        }

        com.ecommerce.backend.entity.TipoProducto tipo = null;
        if (dto.getTipoId() != null) {
            tipo = tipoProductoRepository.findById(dto.getTipoId())
                    .filter(t -> !Boolean.TRUE.equals(t.getIsDeleted()))
                    .orElse(null);
        }
        if (tipo == null) {
            tipo = tipoProductoRepository.findByCodigoAndIsDeletedFalse("HW")
                    .or(() -> tipoProductoRepository.findByCodigoAndIsDeletedFalse("FISICO"))
                    .or(() -> tipoProductoRepository.findAll().stream().filter(t -> !Boolean.TRUE.equals(t.getIsDeleted())).findFirst())
                    .orElseGet(() -> tipoProductoRepository.save(com.ecommerce.backend.entity.TipoProducto.builder().codigo("HW").descripcion("Hardware").build()));
        }

        boolean activo = dto.getActivo() != null ? dto.getActivo() : true;
        boolean vendible = dto.getVendible() != null ? dto.getVendible() : true;
        if ("draft".equalsIgnoreCase(dto.getEstado()) || "borrador".equalsIgnoreCase(dto.getEstado())) {
            activo = false;
            vendible = false;
        } else if ("active".equalsIgnoreCase(dto.getEstado()) || "publicado".equalsIgnoreCase(dto.getEstado())) {
            activo = true;
            vendible = true;
        } else if ("inactive".equalsIgnoreCase(dto.getEstado()) || "desactivado".equalsIgnoreCase(dto.getEstado())) {
            activo = false;
            vendible = false;
        }

        Producto producto = Producto.builder()
                .sku(sku)
                .nombre(dto.getNombre().trim())
                .slug(slug)
                .descripcion(dto.getDescripcion())
                .categoria(categoria)
                .marca(marca)
                .tipo(tipo)
                .precioBase(dto.getPrecioBase())
                .costoReferencia(dto.getCostoReferencia())
                .activo(activo)
                .vendible(vendible)
                .requiereSerie(Boolean.TRUE.equals(dto.getRequiereSerie()))
                .requiereEnvio(dto.getRequiereEnvio() != null ? dto.getRequiereEnvio() : true)
                .isDeleted(false)
                .build();
        producto = productoRepository.save(producto);

        int stockInicial = dto.getStockInicial() != null ? Math.max(0, dto.getStockInicial()) : 0;
        UUID almacenId = existenciaRepository.findFirstByIsDeletedFalse()
                .map(Existencia::getAlmacenId)
                .orElse(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        Existencia existencia = Existencia.builder()
                .producto(producto)
                .almacenId(almacenId)
                .cantidad(stockInicial)
                .reservado(0)
                .version(0)
                .isDeleted(false)
                .build();
        existenciaRepository.save(existencia);

        guardarImagenesGaleria(producto, dto.getImagenes(), dto.getImagenPrincipalUrl());

        new com.ecommerce.backend.service.ProductTechnicalData(atributoTecnicoRepository, valorAtributoProductoRepository, categoriaRepository,attributeRestorer::restore).save(producto, dto.getFichaTecnica());

        return construirAdminDetalleDto(producto);
    }

    @Override
    @Transactional
    public AdminProductoDetalleDto actualizarProducto(UUID id, com.ecommerce.backend.dto.AdminProductoDto dto) {
        validarGaleriaImagenes(dto.getImagenes());

        Producto producto = productoRepository.findById(id)
                .filter(p -> !Boolean.TRUE.equals(p.getIsDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado con ID: " + id));
        var previousSchema = com.ecommerce.backend.service.ProductTechnicalData.schema(producto.getCategoria(), categoriaRepository);

        if (dto.getSku() != null && !dto.getSku().isBlank()) {
            String skuLimpio = dto.getSku().trim();
            if (productoRepository.existsBySkuAndIdNotAndIsDeletedFalse(skuLimpio, id)) {
                throw new BadRequestException("Ya existe otro producto con el SKU: " + skuLimpio);
            }
            producto.setSku(skuLimpio);
        }

        if (dto.getSlug() != null && !dto.getSlug().isBlank()) {
            String slugLimpio = dto.getSlug().trim().toLowerCase(Locale.ROOT);
            if (productoRepository.existsBySlugAndIdNotAndIsDeletedFalse(slugLimpio, id)) {
                throw new BadRequestException("Ya existe otro producto con el slug: " + slugLimpio);
            }
            producto.setSlug(slugLimpio);
        }

        if (dto.getNombre() != null && !dto.getNombre().isBlank()) {
            producto.setNombre(dto.getNombre().trim());
        }
        if (dto.getDescripcion() != null) {
            producto.setDescripcion(dto.getDescripcion());
        }

        if (dto.getCategoriaId() != null) {
            com.ecommerce.backend.entity.Categoria cat = categoriaRepository.findById(dto.getCategoriaId())
                    .filter(c -> !Boolean.TRUE.equals(c.getIsDeleted()))
                    .orElseThrow(() -> new ResourceNotFoundException("Categoría no encontrada con ID: " + dto.getCategoriaId()));
            producto.setCategoria(cat);
        } else if (dto.getCategoriaNombre() != null && !dto.getCategoriaNombre().isBlank()) {
            String slugCat = dto.getCategoriaNombre().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
            categoriaRepository.findBySlugAndIsDeletedFalse(slugCat)
                    .or(() -> categoriaRepository.findAll().stream().filter(c -> !Boolean.TRUE.equals(c.getIsDeleted()) && c.getNombre().equalsIgnoreCase(dto.getCategoriaNombre().trim())).findFirst())
                    .ifPresent(producto::setCategoria);
        }

        if (dto.getMarcaId() != null) {
            com.ecommerce.backend.entity.Marca marca = marcaRepository.findById(dto.getMarcaId())
                    .filter(m -> !Boolean.TRUE.equals(m.getIsDeleted()))
                    .orElseThrow(() -> new ResourceNotFoundException("Marca no encontrada con ID: " + dto.getMarcaId()));
            producto.setMarca(marca);
        } else if (dto.getMarcaNombre() != null && !dto.getMarcaNombre().isBlank()) {
            String slugMarca = dto.getMarcaNombre().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
            com.ecommerce.backend.entity.Marca m = marcaRepository.findBySlugAndIsDeletedFalse(slugMarca)
                    .or(() -> marcaRepository.findAll().stream().filter(x -> !Boolean.TRUE.equals(x.getIsDeleted()) && x.getNombre().equalsIgnoreCase(dto.getMarcaNombre().trim())).findFirst())
                    .orElseGet(() -> marcaRepository.save(com.ecommerce.backend.entity.Marca.builder()
                            .nombre(dto.getMarcaNombre().trim())
                            .slug(slugMarca)
                            .build()));
            producto.setMarca(m);
        }

        if (dto.getPrecioBase() != null) {
            if (dto.getPrecioBase().signum() < 0) {
                throw new BadRequestException("El precio base no puede ser negativo");
            }
            producto.setPrecioBase(dto.getPrecioBase());
        }

        if (dto.getCostoReferencia() != null) {
            if (dto.getCostoReferencia().signum() < 0) {
                throw new BadRequestException("El costo de referencia no puede ser negativo");
            }
            producto.setCostoReferencia(dto.getCostoReferencia());
        }

        if (dto.getEstado() != null) {
            if ("draft".equalsIgnoreCase(dto.getEstado()) || "borrador".equalsIgnoreCase(dto.getEstado())) {
                producto.setActivo(false);
                producto.setVendible(false);
            } else if ("active".equalsIgnoreCase(dto.getEstado()) || "publicado".equalsIgnoreCase(dto.getEstado())) {
                producto.setActivo(true);
                producto.setVendible(true);
            } else if ("inactive".equalsIgnoreCase(dto.getEstado()) || "desactivado".equalsIgnoreCase(dto.getEstado())) {
                producto.setActivo(false);
                producto.setVendible(false);
            }
        } else {
            if (dto.getActivo() != null) producto.setActivo(dto.getActivo());
            if (dto.getVendible() != null) producto.setVendible(dto.getVendible());
        }

        if (dto.getRequiereSerie() != null) producto.setRequiereSerie(dto.getRequiereSerie());
        if (dto.getRequiereEnvio() != null) producto.setRequiereEnvio(dto.getRequiereEnvio());

        // La edición comercial de un producto NO altera el stock/inventario físico.
        // Los ajustes de stock deben realizarse a través del módulo de inventario por almacén.

        if (dto.getImagenes() != null) {
            List<ImagenProducto> existentes = imagenProductoRepository.findByProductoIdAndIsDeletedFalseOrderByPosicionAsc(id);
            for (ImagenProducto ex : existentes) {
                ex.setPrincipal(false);
                ex.setIsDeleted(true);
            }
            imagenProductoRepository.saveAll(existentes);
            imagenProductoRepository.flush();
            if (!dto.getImagenes().isEmpty()) {
                guardarImagenesGaleria(producto, dto.getImagenes(), dto.getImagenPrincipalUrl());
                imagenProductoRepository.flush();
            }
        } else if (dto.getImagenPrincipalUrl() != null && !dto.getImagenPrincipalUrl().isBlank()) {
            validarUrlHttps(dto.getImagenPrincipalUrl());
            var optImg = imagenProductoRepository.findFirstByProductoIdAndPrincipalTrueAndIsDeletedFalse(id);
            if (optImg.isPresent()) {
                ImagenProducto img = optImg.get();
                img.setUrl(dto.getImagenPrincipalUrl().trim());
                imagenProductoRepository.save(img);
            } else {
                ImagenProducto img = ImagenProducto.builder()
                        .producto(producto)
                        .url(dto.getImagenPrincipalUrl().trim())
                        .textoAlternativo(producto.getNombre())
                        .posicion(0)
                        .principal(true)
                        .isDeleted(false)
                        .build();
                imagenProductoRepository.save(img);
            }
        }

        var technical = new com.ecommerce.backend.service.ProductTechnicalData(atributoTecnicoRepository, valorAtributoProductoRepository, categoriaRepository,attributeRestorer::restore);
        var nextSchema = com.ecommerce.backend.service.ProductTechnicalData.schema(producto.getCategoria(), categoriaRepository);
        if (dto.getFichaTecnica() == null && !Objects.equals(previousSchema, nextSchema)) technical.retainForSchema(producto,nextSchema);
        technical.save(producto, dto.getFichaTecnica());
        return construirAdminDetalleDto(productoRepository.save(producto));
    }

    @Override
    @Transactional
    public AdminProductoDetalleDto cambiarEstado(UUID id, String nuevoEstado) {
        Producto producto = productoRepository.findById(id)
                .filter(p -> !Boolean.TRUE.equals(p.getIsDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado con ID: " + id));

        if ("publicar".equalsIgnoreCase(nuevoEstado) || "active".equalsIgnoreCase(nuevoEstado) || "publicado".equalsIgnoreCase(nuevoEstado)) {
            producto.setActivo(true);
            producto.setVendible(true);
        } else if ("desactivar".equalsIgnoreCase(nuevoEstado) || "inactive".equalsIgnoreCase(nuevoEstado) || "desactivado".equalsIgnoreCase(nuevoEstado)) {
            producto.setActivo(false);
            producto.setVendible(false);
        } else if ("borrador".equalsIgnoreCase(nuevoEstado) || "draft".equalsIgnoreCase(nuevoEstado)) {
            producto.setActivo(false);
            producto.setVendible(false);
        } else {
            throw new BadRequestException("Estado desconocido o no soportado: " + nuevoEstado);
        }

        return construirAdminDetalleDto(productoRepository.save(producto));
    }

    @Override
    @Transactional
    public void eliminarProducto(UUID id) {
        Producto producto = productoRepository.findById(id)
                .filter(p -> !Boolean.TRUE.equals(p.getIsDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado con ID: " + id));

        producto.setIsDeleted(true);
        producto.setActivo(false);
        producto.setVendible(false);
        productoRepository.save(producto);
    }

    private void guardarImagenesGaleria(Producto producto, List<com.ecommerce.backend.dto.ImagenProductoDto> imagenesDto, String fallbackPrincipalUrl) {
        if (imagenesDto != null && !imagenesDto.isEmpty()) {
            boolean hayPrincipalExplicita = imagenesDto.stream()
                    .anyMatch(img -> Boolean.TRUE.equals(img.getPrincipal()));
            boolean principalAsignada = false;
            for (int i = 0; i < imagenesDto.size(); i++) {
                var imgDto = imagenesDto.get(i);
                if (imgDto.getUrl() == null || imgDto.getUrl().isBlank()) continue;
                boolean isPrincipal;
                if (hayPrincipalExplicita) {
                    isPrincipal = Boolean.TRUE.equals(imgDto.getPrincipal()) && !principalAsignada;
                } else {
                    isPrincipal = (i == 0);
                }
                if (isPrincipal) principalAsignada = true;
                ImagenProducto img = ImagenProducto.builder()
                        .producto(producto)
                        .url(imgDto.getUrl().trim())
                        .textoAlternativo(imgDto.getTextoAlternativo() != null && !imgDto.getTextoAlternativo().isBlank()
                                ? imgDto.getTextoAlternativo().trim() : producto.getNombre())
                        .posicion(imgDto.getPosicion() != null ? imgDto.getPosicion() : i)
                        .principal(isPrincipal)
                        .isDeleted(false)
                        .build();
                imagenProductoRepository.save(img);
            }
        } else if (fallbackPrincipalUrl != null && !fallbackPrincipalUrl.isBlank()) {
            ImagenProducto img = ImagenProducto.builder()
                    .producto(producto)
                    .url(fallbackPrincipalUrl.trim())
                    .textoAlternativo(producto.getNombre())
                    .posicion(0)
                    .principal(true)
                    .isDeleted(false)
                    .build();
            imagenProductoRepository.save(img);
        }
    }

    private void validarGaleriaImagenes(List<com.ecommerce.backend.dto.ImagenProductoDto> imagenes) {
        if (imagenes == null || imagenes.isEmpty()) {
            return;
        }

        if (imagenes.size() > 8) {
            throw new BadRequestException("La galería de imágenes no puede exceder el límite de 8 imágenes");
        }

        Set<String> urlsUnicas = new HashSet<>();
        long principales = 0;

        for (var img : imagenes) {
            if (img == null || img.getUrl() == null || img.getUrl().isBlank()) {
                throw new BadRequestException("Cada elemento de la galería debe incluir una URL válida");
            }

            String url = img.getUrl().trim();
            validarUrlHttps(url);

            if (!urlsUnicas.add(url.toLowerCase(Locale.ROOT))) {
                throw new BadRequestException("No se permiten URLs duplicadas en la galería: " + url);
            }

            if (img.getTextoAlternativo() != null && img.getTextoAlternativo().length() > 220) {
                throw new BadRequestException("El texto alternativo de la imagen excede el límite de 220 caracteres");
            }

            if (Boolean.TRUE.equals(img.getPrincipal())) {
                principales++;
            }
        }

        if (principales > 1) {
            throw new BadRequestException("Solo una imagen de la galería puede marcarse como principal");
        }
    }

    private void validarUrlHttps(String urlStr) {
        if (urlStr == null || urlStr.isBlank()) {
            return;
        }
        String trimmedUrl = urlStr.trim();
        try {
            URI uri = URI.create(trimmedUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new BadRequestException("Las imágenes deben utilizar URLs con protocolo seguro HTTPS: " + trimmedUrl);
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new BadRequestException("La URL de la imagen no contiene un host válido: " + trimmedUrl);
            }
            if (uri.getUserInfo() != null) {
                throw new BadRequestException("Las URLs de imágenes no pueden contener credenciales de usuario");
            }
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("URL de imagen inválida o malformada: " + trimmedUrl);
        }
    }
}
