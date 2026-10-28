package com.ecommerce.backend.service.impl;

import com.ecommerce.backend.dto.*;
import com.ecommerce.backend.entity.*;
import com.ecommerce.backend.exception.BadRequestException;
import com.ecommerce.backend.exception.ResourceNotFoundException;
import com.ecommerce.backend.repository.*;
import com.ecommerce.backend.service.ConfiguracionPcService;
import com.ecommerce.backend.service.PcBuilderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConfiguracionPcServiceImpl implements ConfiguracionPcService {

    private final ConfiguracionPcRepository configuracionRepository;
    private final ComponenteConfiguracionRepository componenteRepository;
    private final TipoComponenteRepository tipoComponenteRepository;
    private final ProductoRepository productoRepository;
    private final ExistenciaRepository existenciaRepository;
    private final PcBuilderService pcBuilderService;

    @Override
    @Transactional
    public ConfiguracionPcDto crear(UUID usuarioId, CrearConfiguracionRequestDto request) {
        ConfiguracionPc configuracion = ConfiguracionPc.builder()
                .usuarioId(usuarioId)
                .nombre(limpiarNombre(request.getNombre()))
                .presupuesto(request.getPresupuesto())
                .moneda(normalizarMoneda(request.getMoneda()))
                .estado("BORRADOR")
                .totalEstimado(BigDecimal.ZERO)
                .build();
        return mapear(configuracionRepository.save(configuracion), List.of());
    }

    @Override
    public List<ConfiguracionPcDto> listar(UUID usuarioId) {
        return configuracionRepository.findByUsuarioIdAndIsDeletedFalseOrderByUpdatedAtDesc(usuarioId).stream()
                .map(configuracion -> mapear(configuracion,
                        componenteRepository.findByConfiguracionIdAndIsDeletedFalseOrderByCreatedAtAsc(configuracion.getId())))
                .toList();
    }

    @Override
    public ConfiguracionPcDto obtener(UUID usuarioId, UUID id) {
        ConfiguracionPc configuracion = buscarConfiguracion(usuarioId, id);
        return mapear(configuracion,
                componenteRepository.findByConfiguracionIdAndIsDeletedFalseOrderByCreatedAtAsc(id));
    }

    @Override
    @Transactional
    public AplicarCambioConfiguracionResponseDto aplicarCambio(UUID usuarioId, AplicarCambioConfiguracionRequestDto request) {
        ConfiguracionPc configuracion = buscarConfiguracion(usuarioId, request.getConfiguracionId());
        if (!"BORRADOR".equalsIgnoreCase(configuracion.getEstado())) {
            throw new BadRequestException("Solo se pueden modificar configuraciones en estado BORRADOR");
        }

        String codigoTipo = normalizarCodigo(request.getTipoComponente());
        TipoComponente tipo = tipoComponenteRepository.findByCodigoIgnoreCaseAndIsDeletedFalse(codigoTipo)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No existe el tipo de componente activo: " + request.getTipoComponente()));
        Producto producto = productoRepository.findConDetallesPorId(request.getProductoId())
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado: " + request.getProductoId()));

        if (!Boolean.TRUE.equals(producto.getActivo()) || !Boolean.TRUE.equals(producto.getVendible())) {
            throw new BadRequestException("El producto no está activo y disponible para cotización");
        }
        validarProductoPerteneceAlTipo(producto, codigoTipo);

        List<ComponenteConfiguracion> componentes = new ArrayList<>(
                componenteRepository.findByConfiguracionIdAndIsDeletedFalseOrderByCreatedAtAsc(configuracion.getId()));
        ComponenteConfiguracion actual = componentes.stream()
                .filter(item -> item.getTipoComponente().getId().equals(tipo.getId()))
                .findFirst().orElse(null);

        if (actual != null && Boolean.TRUE.equals(actual.getBloqueado())) {
            boolean mismoProducto = actual.getProducto().getId().equals(producto.getId());
            boolean mismaCantidad = Objects.equals(actual.getCantidad(), request.getCantidad());
            if (!mismoProducto || !mismaCantidad) {
                throw new BadRequestException("El componente " + tipo.getNombre()
                        + " está bloqueado; desbloquéalo antes de sustituirlo o cambiar su cantidad");
            }
        }

        Map<String, UUID> seleccion = construirSeleccion(componentes, tipo.getId(), codigoTipo, producto.getId());
        ValidarCambioResponseDto validacion = pcBuilderService.validarCambio(
                construirSolicitudValidacion(seleccion, componentes, request.getBiosInstalada()));
        if ("INCOMPATIBLE".equalsIgnoreCase(validacion.getEstadoCompatibilidad())) {
            throw new BadRequestException("El cambio no se aplicó porque la configuración es incompatible: "
                    + String.join(" ", Optional.ofNullable(validacion.getMensajes()).orElse(List.of())));
        }

        if (actual == null) {
            actual = ComponenteConfiguracion.builder()
                    .configuracion(configuracion)
                    .tipoComponente(tipo)
                    .producto(producto)
                    .cantidad(request.getCantidad())
                    .bloqueado(Boolean.TRUE.equals(request.getBloqueado()))
                    .precioSnapshot(producto.getPrecioVenta())
                    .build();
            componenteRepository.save(actual);
            componentes.add(actual);
        } else if (!Boolean.TRUE.equals(actual.getBloqueado())) {
            actual.setProducto(producto);
            actual.setCantidad(request.getCantidad());
            actual.setBloqueado(Boolean.TRUE.equals(request.getBloqueado()));
            actual.setPrecioSnapshot(producto.getPrecioVenta());
            componenteRepository.save(actual);
        } else {
            // El mismo producto y cantidad pueden desbloquearse de forma explícita.
            actual.setBloqueado(Boolean.TRUE.equals(request.getBloqueado()));
            componenteRepository.save(actual);
        }

        BigDecimal total = componentes.stream()
                .filter(item -> !Boolean.TRUE.equals(item.getIsDeleted()))
                .map(item -> item.getPrecioSnapshot().multiply(BigDecimal.valueOf(item.getCantidad())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(configuracion.getPresupuesto()) > 0) {
            throw new BadRequestException("El cambio supera el presupuesto: total " + total
                    + " " + configuracion.getMoneda() + " frente a " + configuracion.getPresupuesto());
        }
        configuracion.setTotalEstimado(total);
        configuracionRepository.save(configuracion);

        return AplicarCambioConfiguracionResponseDto.builder()
                .configuracion(mapear(configuracion, componentes))
                .compatibilidad(validacion)
                .build();
    }

    private ConfiguracionPc buscarConfiguracion(UUID usuarioId, UUID id) {
        return configuracionRepository.findByIdAndUsuarioIdAndIsDeletedFalse(id, usuarioId)
                .orElseThrow(() -> new ResourceNotFoundException("Configuración no encontrada: " + id));
    }

    private void validarProductoPerteneceAlTipo(Producto producto, String codigoTipo) {
        if (producto.getCategoria() == null) {
            throw new BadRequestException("El producto no tiene una categoría válida");
        }
        String categoria = normalizarCodigo(producto.getCategoria().getAcronimo() != null
                ? producto.getCategoria().getAcronimo() : producto.getCategoria().getSlug());
        Set<String> equivalentes = equivalentesTipo(codigoTipo);
        if (!equivalentes.contains(categoria)) {
            throw new BadRequestException("El producto pertenece a " + producto.getCategoria().getNombre()
                    + " y no al tipo " + codigoTipo);
        }
    }

    private Map<String, UUID> construirSeleccion(List<ComponenteConfiguracion> componentes, UUID tipoReemplazado,
                                                  String codigoNuevo, UUID productoNuevo) {
        Map<String, UUID> seleccion = new LinkedHashMap<>();
        for (ComponenteConfiguracion item : componentes) {
            if (!item.getTipoComponente().getId().equals(tipoReemplazado)) {
                seleccion.put(normalizarCodigo(item.getTipoComponente().getCodigo()), item.getProducto().getId());
            }
        }
        seleccion.put(codigoNuevo, productoNuevo);
        return seleccion;
    }

    private ValidarCambioRequestDto construirSolicitudValidacion(Map<String, UUID> seleccion,
                                                                  List<ComponenteConfiguracion> componentes,
                                                                  String biosInstalada) {
        List<UUID> ids = new ArrayList<>(seleccion.values());
        List<UUID> bloqueados = componentes.stream().filter(c -> Boolean.TRUE.equals(c.getBloqueado()))
                .map(c -> c.getProducto().getId()).toList();
        return ValidarCambioRequestDto.builder()
                .cpuId(valorTipo(seleccion, "CPU", "PROCESADOR", "PROCESADORES"))
                .placaId(valorTipo(seleccion, "PLACA", "MOTHERBOARD", "PLACAS-MADRE", "PLACAS MADRE"))
                .ramId(valorTipo(seleccion, "RAM", "MEMORIA", "MEMORIA-RAM"))
                .gpuId(valorTipo(seleccion, "GPU", "TARJETA-GRAFICA", "TARJETAS-GRAFICAS"))
                .fuenteId(valorTipo(seleccion, "PSU", "FUENTE", "FUENTES-DE-PODER"))
                .gabineteId(valorTipo(seleccion, "CASE", "GABINETE", "GABINETES"))
                .refrigeracionId(valorTipo(seleccion, "COOLER", "REFRIGERACION", "ENFRIAMIENTO-LIQUIDO"))
                .biosInstalada(biosInstalada)
                .componentesActuales(ids)
                .componentesBloqueados(bloqueados)
                .build();
    }

    private UUID valorTipo(Map<String, UUID> seleccion, String... codigos) {
        for (String codigo : codigos) {
            UUID valor = seleccion.get(normalizarCodigo(codigo));
            if (valor != null) return valor;
        }
        return null;
    }

    private ConfiguracionPcDto mapear(ConfiguracionPc configuracion, List<ComponenteConfiguracion> componentes) {
        List<ComponenteConfiguracionDto> items = componentes.stream().map(item -> {
            int stock = Optional.ofNullable(existenciaRepository
                    .findStockDisponiblePorProductoId(item.getProducto().getId())).orElse(0);
            return ComponenteConfiguracionDto.builder()
                    .id(item.getId())
                    .tipoComponenteId(item.getTipoComponente().getId())
                    .tipoCodigo(item.getTipoComponente().getCodigo())
                    .tipoNombre(item.getTipoComponente().getNombre())
                    .productoId(item.getProducto().getId())
                    .sku(item.getProducto().getSku())
                    .productoNombre(item.getProducto().getNombre())
                    .cantidad(item.getCantidad())
                    .bloqueado(item.getBloqueado())
                    .precioSnapshot(item.getPrecioSnapshot())
                    .subtotal(item.getPrecioSnapshot().multiply(BigDecimal.valueOf(item.getCantidad())))
                    .stockDisponible(stock)
                    .build();
        }).toList();
        return ConfiguracionPcDto.builder()
                .id(configuracion.getId())
                .nombre(configuracion.getNombre())
                .presupuesto(configuracion.getPresupuesto())
                .moneda(configuracion.getMoneda())
                .estado(configuracion.getEstado())
                .totalEstimado(configuracion.getTotalEstimado())
                .presupuestoRestante(configuracion.getPresupuesto().subtract(configuracion.getTotalEstimado()))
                .componentes(items)
                .build();
    }

    private Set<String> equivalentesTipo(String codigo) {
        return switch (codigo) {
            case "CPU", "PROCESADOR", "PROCESADORES" -> Set.of("CPU", "PROCESADOR", "PROCESADORES");
            case "PLACA", "MOTHERBOARD", "PLACAS-MADRE", "PLACAS MADRE" ->
                    Set.of("PLACA", "MOTHERBOARD", "PLACAS-MADRE", "PLACAS MADRE", "MB");
            case "RAM", "MEMORIA", "MEMORIA-RAM" -> Set.of("RAM", "MEMORIA", "MEMORIA-RAM");
            case "GPU", "TARJETA-GRAFICA", "TARJETAS-GRAFICAS" ->
                    Set.of("GPU", "TARJETA-GRAFICA", "TARJETAS-GRAFICAS");
            case "PSU", "FUENTE", "FUENTES-DE-PODER" -> Set.of("PSU", "FUENTE", "FUENTES-DE-PODER");
            case "CASE", "GABINETE", "GABINETES" -> Set.of("CASE", "GABINETE", "GABINETES");
            case "COOLER", "REFRIGERACION", "ENFRIAMIENTO-LIQUIDO" ->
                    Set.of("COOLER", "REFRIGERACION", "ENFRIAMIENTO-LIQUIDO");
            default -> Set.of(codigo);
        };
    }

    private String normalizarCodigo(String valor) {
        return Optional.ofNullable(valor).orElse("").trim().toUpperCase(Locale.ROOT)
                .replace('_', '-').replaceAll("\\s+", " ");
    }

    private String normalizarMoneda(String moneda) {
        return Optional.ofNullable(moneda).filter(value -> !value.isBlank()).orElse("PEN")
                .toUpperCase(Locale.ROOT);
    }

    private String limpiarNombre(String nombre) {
        return nombre == null || nombre.isBlank() ? null : nombre.trim();
    }
}
