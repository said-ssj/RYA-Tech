package com.ecommerce.backend.service.impl;

import com.ecommerce.backend.dto.*;
import com.ecommerce.backend.entity.*;
import com.ecommerce.backend.exception.BadRequestException;
import com.ecommerce.backend.exception.ResourceNotFoundException;
import com.ecommerce.backend.mapper.ProductoMapper;
import com.ecommerce.backend.repository.*;
import com.ecommerce.backend.service.PcBuilderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PcBuilderServiceImpl implements PcBuilderService {

    private final TipoComponenteRepository tipoComponenteRepository;
    private final ProductoRepository productoRepository;
    private final CategoriaRepository categoriaRepository;
    private final ImagenProductoRepository imagenProductoRepository;
    private final ExistenciaRepository existenciaRepository;
    private final EspecificacionCpuRepository especificacionCpuRepository;
    private final RevisionPlacaRepository revisionPlacaRepository;
    private final VersionBiosRepository versionBiosRepository;
    private final CompatibilidadCpuPlacaRepository compatibilidadCpuPlacaRepository;
    private final ValorAtributoProductoRepository valorAtributoProductoRepository;
    private final ProductoMapper productoMapper;
    private final FamiliaPerfilAsesoriaRepository familiaPerfilAsesoriaRepository;
    private final PerfilAsesoriaRepository perfilAsesoriaRepository;
    private final CompatibilidadCpuChipsetRepository compatibilidadCpuChipsetRepository;

    @Override
    public PcBuilderInicialDto obtenerDatosIniciales() {
        List<TipoComponenteResumenDto> tipos = tipoComponenteRepository.findByIsDeletedFalseOrderByCodigoAsc().stream()
                .map(tipo -> TipoComponenteResumenDto.builder()
                        .id(tipo.getId())
                        .code(tipo.getCodigo())
                        .name(tipo.getNombre())
                        .obligatorio(tipo.getObligatorio())
                        .build())
                .toList();
        if (tipos.isEmpty()) {
            tipos = categoriaRepository.findByIsDeletedFalseOrderByPosicionAsc().stream()
                    .map(categoria -> TipoComponenteResumenDto.builder()
                            .id(categoria.getId())
                            .code(categoria.getAcronimo() != null && !categoria.getAcronimo().isBlank()
                                    ? categoria.getAcronimo() : categoria.getSlug())
                            .name(categoria.getNombre())
                            .obligatorio(false)
                            .build())
                    .toList();
        }

        List<Map<String, Object>> profileFamilies = familiaPerfilAsesoriaRepository.findByActivoTrueAndIsDeletedFalseOrderByPosicionAsc().stream()
                .map(f -> {
                    Map<String, Object> famMap = new LinkedHashMap<>();
                    famMap.put("id", f.getId().toString());
                    famMap.put("code", f.getCodigo());
                    famMap.put("name", f.getNombre());
                    famMap.put("description", f.getDescripcion());
                    famMap.put("icon", f.getIcono());
                    List<Map<String, Object>> perfiles = f.getPerfiles().stream()
                            .filter(p -> Boolean.TRUE.equals(p.getActivo()) && !Boolean.TRUE.equals(p.getIsDeleted()))
                            .map(p -> {
                                Map<String, Object> perfMap = new LinkedHashMap<>();
                                perfMap.put("id", p.getId().toString());
                                perfMap.put("code", p.getCodigo());
                                perfMap.put("name", p.getNombre());
                                perfMap.put("description", p.getDescripcion());
                                List<Map<String, Object>> needs = p.getNecesidades().stream()
                                        .filter(n -> Boolean.TRUE.equals(n.getActivo()))
                                        .map(n -> Map.<String, Object>of(
                                                "id", n.getId().toString(),
                                                "code", n.getCodigo() != null ? n.getCodigo() : n.getId().toString(),
                                                "name", n.getNombre()
                                        ))
                                        .toList();
                                perfMap.put("needs", needs);
                                return perfMap;
                            })
                            .toList();
                    famMap.put("profiles", perfiles);
                    return famMap;
                })
                .toList();

        return PcBuilderInicialDto.builder()
                .componentTypes(tipos)
                .profileFamilies(profileFamilies)
                .resolutions(List.of())
                .build();
    }

    @Override
    public ComponenteTipoDto listarComponentesPorTipo(String tipo) {
        if (tipo == null || tipo.trim().isEmpty()) {
            throw new BadRequestException("El tipo de componente no puede ser nulo o vacío");
        }

        String tipoNorm = tipo.trim();

        // 1. Buscar en rya.tipos_componente por código
        Optional<TipoComponente> tipoCompOpt = tipoComponenteRepository.findByCodigoIgnoreCaseAndIsDeletedFalse(tipoNorm);

        // 2. Buscar productos asociados por categoría (slug o acrónimo) o tipo
        List<Producto> productos = new ArrayList<>();
        String nombreTipo = tipoNorm;
        UUID tipoId = null;
        boolean obligatorio = true;

        if (tipoCompOpt.isPresent()) {
            TipoComponente tc = tipoCompOpt.get();
            tipoId = tc.getId();
            nombreTipo = tc.getNombre();
            obligatorio = Boolean.TRUE.equals(tc.getObligatorio());

            // Buscar por categoría vinculada a dicho componente
            productos = productoRepository.findActivosPorCategoriaAcronimo(tc.getCodigo());
            if (productos.isEmpty()) {
                productos = productoRepository.findActivosPorCategoriaSlug(tc.getCodigo().toLowerCase());
            }
        }

        // Si no se encontraron por código, buscar directamente por slug de categoría
        if (productos.isEmpty()) {
            Optional<Categoria> catOpt = categoriaRepository.findBySlugAndIsDeletedFalse(tipoNorm.toLowerCase());
            if (catOpt.isEmpty()) {
                catOpt = categoriaRepository.findByAcronimoIgnoreCaseAndIsDeletedFalse(tipoNorm);
            }
            if (catOpt.isPresent()) {
                Categoria cat = catOpt.get();
                nombreTipo = cat.getNombre();
                productos = productoRepository.findActivosPorCategoriaId(cat.getId());
            }
        }

        // Mapear con imágenes principales y stock para evitar N+1
        List<ProductoResumenDto> productosDto = mapearProductosConStockEImagenes(productos);

        return ComponenteTipoDto.builder()
                .id(tipoId)
                .codigo(tipoNorm.toUpperCase())
                .nombre(nombreTipo)
                .obligatorio(obligatorio)
                .productos(productosDto)
                .build();
    }

    @Override
    public List<ProductoResumenDto> obtenerComponentesCompatibles(String tipo, List<UUID> componentesActuales) {
        ComponenteTipoDto tipoDto = listarComponentesPorTipo(tipo);
        if (componentesActuales == null || componentesActuales.isEmpty()) {
            return tipoDto.getProductos();
        }

        List<ProductoResumenDto> candidatos = tipoDto.getProductos();

        // Starting a build needs no pair validation. Existing relationships must be verified.
        return candidatos.stream()
                .filter(candidato -> noIntroduceIncompatibilidad(componentesActuales, candidato.getId()))
                .toList();
    }

    private boolean noIntroduceIncompatibilidad(List<UUID> actuales, UUID candidatoId) {
        try {
            LinkedHashMap<String, UUID> seleccion = new LinkedHashMap<>();
            List<UUID> ids = new ArrayList<>(actuales);
            ids.add(candidatoId);
            for (UUID id : ids) {
                Producto producto = productoRepository.findConDetallesPorId(id).orElse(null);
                if (producto == null || producto.getCategoria() == null) return false;
                String codigo = producto.getCategoria().getAcronimo();
                if (codigo == null || codigo.isBlank()) codigo = producto.getCategoria().getSlug();
                seleccion.put(normalizarTipo(codigo), id);
            }
            ValidarCambioRequestDto request = ValidarCambioRequestDto.builder()
                    .cpuId(valorTipo(seleccion, "CPU", "PROCESADOR", "PROCESADORES"))
                    .placaId(valorTipo(seleccion, "PLACA", "MB", "MOTHERBOARD", "PLACAS-MADRE", "PLACASMADRE"))
                    .ramId(valorTipo(seleccion, "RAM", "MEMORIA", "MEMORIA-RAM", "MEMORIARAM"))
                    .gpuId(valorTipo(seleccion, "GPU", "TARJETA-GRAFICA", "TARJETAS-GRAFICAS", "TARJETAGRAFICA"))
                    .fuenteId(valorTipo(seleccion, "PSU", "FUENTE", "FUENTES-DE-PODER", "FUENTEDEPODER"))
                    .gabineteId(valorTipo(seleccion, "CASE", "GABINETE", "GABINETES"))
                    .refrigeracionId(valorTipo(seleccion, "COOLER", "REFRIGERACION", "ENFRIAMIENTO-LIQUIDO"))
                    .componentesActuales(new ArrayList<>(new LinkedHashSet<>(seleccion.values())))
                    .build();
            if(request.getCpuId()!=null && request.getPlacaId()!=null
                && !"VERIFICADA".equalsIgnoreCase(validarCpuPlaca(request).getEstadoCompatibilidad())) return false;
            return validarReglasTecnicas(request).stream().allMatch(regla -> "VERIFICADA".equals(regla.estado()));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private UUID valorTipo(Map<String, UUID> seleccion, String... codigos) {
        for (String codigo : codigos) {
            UUID id = seleccion.get(normalizarTipo(codigo));
            if (id != null) return id;
        }
        return null;
    }

    private String normalizarTipo(String valor) {
        String key=java.text.Normalizer.normalize(Optional.ofNullable(valor).orElse(""),java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return switch(key){
            case "CPU","PROCESADOR","PROCESADORES" -> "CPU";
            case "MB","PLACA","PLACAMADRE","PLACASMADRE","MOTHERBOARD","BOARD" -> "PLACA";
            case "RAM","MEMORIA","MEMORIARAM","MEMORIASRAM" -> "RAM";
            case "GPU","TARJETAGRAFICA","TARJETASGRAFICAS" -> "GPU";
            case "PSU","FUENTE","FUENTEDEPODER","FUENTESDEPODER" -> "PSU";
            case "CASE","GABINETE","GABINETES" -> "CASE";
            case "COOLER","REFRIGERACION","ENFRIAMIENTOLIQUIDO" -> "COOLER";
            default -> key;
        };
    }

    @Override
    public ValidarCambioResponseDto validarCambio(ValidarCambioRequestDto request) {
        ValidarCambioResponseDto plataforma = validarCpuPlaca(request);
        List<ResultadoRegla> reglas = validarReglasTecnicas(request);
        return combinarResultados(plataforma, reglas);
    }

    private ValidarCambioResponseDto validarCpuPlaca(ValidarCambioRequestDto request) {
        if (request == null) {
            throw new BadRequestException("El cuerpo de la solicitud no puede estar vacío");
        }

        UUID cpuId = request.getCpuId();
        UUID placaId = request.getPlacaId();
        UUID placaRevisionId = request.getPlacaRevisionId();

        // Una selección incompleta nunca puede declararse como compatibilidad verificada.
        if (cpuId == null || (placaId == null && placaRevisionId == null)) {
            return ValidarCambioResponseDto.builder()
                    .esCompatible(false)
                    .estadoCompatibilidad("POR_VERIFICAR")
                    .mensajes(List.of("Selecciona una CPU y una placa madre con revisión identificada para validar socket y BIOS."))
                    .requiereActualizacionBios(false)
                    .build();
        }

        // Cargar atributos EAV en caso de que los componentes no tengan filas dedicadas en especificaciones_cpu o revisiones_placa
        List<UUID> idsCargar = new ArrayList<>();
        idsCargar.add(cpuId);
        if (placaId != null) idsCargar.add(placaId);
        Map<UUID, Map<String, ValorAtributoProducto>> attrsEav = cargarAtributos(idsCargar);

        // 1. Obtener especificaciones del CPU
        EspecificacionCpu cpu = especificacionCpuRepository.findConProductoPorId(cpuId).orElse(null);
        Map<String, ValorAtributoProducto> attrsCpu = attrsEav.getOrDefault(cpuId, Map.of());
        String socketCpu = cpu != null && cpu.getSocket() != null ? cpu.getSocket() : texto(attrsCpu, "catalog_tech_cpu_socket", "socket_cpu");
        String generacionCpu = cpu != null && cpu.getGeneracion() != null ? cpu.getGeneracion() : texto(attrsCpu, "catalog_tech_cpu_generacion", "generacion_cpu", "catalog_tech_cpu_series");

        if (socketCpu == null) {
            return ValidarCambioResponseDto.builder()
                    .esCompatible(false)
                    .estadoCompatibilidad("POR_VERIFICAR")
                    .mensajes(List.of("No se encontraron especificaciones técnicas de socket para la CPU indicada."))
                    .requiereActualizacionBios(false)
                    .build();
        }

        // 2. Resolver la revisión de placa y sus datos técnicos
        RevisionPlaca revisionPlaca = null;
        if (placaRevisionId != null) {
            revisionPlaca = revisionPlacaRepository.findById(placaRevisionId).orElse(null);
        } else if (placaId != null) {
            List<RevisionPlaca> revisiones = revisionPlacaRepository.findByProductoId(placaId);
            if (!revisiones.isEmpty()) {
                revisionPlaca = revisiones.get(0);
            }
        }

        Map<String, ValorAtributoProducto> attrsPlaca = placaId != null ? attrsEav.getOrDefault(placaId, Map.of()) : Map.of();
        String socketPlaca = revisionPlaca != null && revisionPlaca.getSocket() != null ? revisionPlaca.getSocket() : texto(attrsPlaca, "catalog_tech_board_socket", "socket_placa");
        String chipsetPlaca = revisionPlaca != null && revisionPlaca.getChipset() != null ? revisionPlaca.getChipset() : texto(attrsPlaca, "catalog_tech_board_chipset", "chipset");

        if (socketPlaca == null) {
            return ValidarCambioResponseDto.builder()
                    .esCompatible(false)
                    .estadoCompatibilidad("POR_VERIFICAR")
                    .mensajes(List.of("No se encontraron especificaciones técnicas de socket para la placa madre indicada."))
                    .requiereActualizacionBios(false)
                    .build();
        }

        // 3. Regla obligatoria: Comparar sockets físicos
        if (!socketCpu.equalsIgnoreCase(socketPlaca)) {
            return ValidarCambioResponseDto.builder()
                    .esCompatible(false)
                    .estadoCompatibilidad("INCOMPATIBLE")
                    .mensajes(List.of("Incompatibilidad física de socket: CPU usa " + socketCpu +
                            " y la placa madre usa " + socketPlaca + "."))
                    .requiereActualizacionBios(false)
                    .build();
        }

        // 4. Si existe ficha de revisión de placa y especificación CPU, consultar rya.compatibilidad_cpu_placa
        if (cpu != null && revisionPlaca != null) {
            Optional<CompatibilidadCpuPlaca> compatOpt = compatibilidadCpuPlacaRepository.findConDetalles(cpu.getProductoId(), revisionPlaca.getId());
            if (compatOpt.isPresent()) {
                CompatibilidadCpuPlaca compat = compatOpt.get();
                String soporte = compat.getSoporte();

                if ("NO_SOPORTADO".equalsIgnoreCase(soporte)) {
                    return ValidarCambioResponseDto.builder()
                            .esCompatible(false)
                            .estadoCompatibilidad("INCOMPATIBLE")
                            .mensajes(List.of("Incompatible: El fabricante indica expresamente que esta CPU no es soportada por esta placa madre (" +
                                    (compat.getCondiciones() != null ? compat.getCondiciones() : "Sin soporte oficial") + ")."))
                            .requiereActualizacionBios(false)
                            .condiciones(compat.getCondiciones())
                            .build();
                }

                if ("POR_VERIFICAR".equalsIgnoreCase(soporte)) {
                    return ValidarCambioResponseDto.builder()
                            .esCompatible(false)
                            .estadoCompatibilidad("POR_VERIFICAR")
                            .mensajes(List.of("El soporte de esta CPU en la placa aún no ha sido certificado o se encuentra pendiente de verificación por el fabricante."))
                            .requiereActualizacionBios(false)
                            .condiciones(compat.getCondiciones())
                            .build();
                }

                if ("SOPORTADO".equalsIgnoreCase(soporte)) {
                    VersionBios biosMinima = compat.getBiosMinima();
                    String nombreBiosMinima = biosMinima != null ? biosMinima.getVersion() : null;

                    if (biosMinima != null && (request.getBiosInstalada() == null || request.getBiosInstalada().isBlank())) {
                        return ValidarCambioResponseDto.builder()
                                .esCompatible(true)
                                .estadoCompatibilidad("CONDICIONAL")
                                .requiereActualizacionBios(false)
                                .biosMinimaRequerida(nombreBiosMinima)
                                .condiciones(compat.getCondiciones())
                                .mensajes(List.of("La combinación tiene soporte oficial, pero debes confirmar que la placa use la BIOS " + nombreBiosMinima + " o una versión posterior."))
                                .build();
                    }

                    if (biosMinima != null) {
                        Optional<VersionBios> biosInstaladaOpt = versionBiosRepository.findByPlacaRevisionIdAndVersionIgnoreCase(
                                revisionPlaca.getId(), request.getBiosInstalada().trim());

                        if (biosInstaladaOpt.isEmpty()) {
                            return ValidarCambioResponseDto.builder()
                                    .esCompatible(false)
                                    .estadoCompatibilidad("POR_VERIFICAR")
                                    .requiereActualizacionBios(false)
                                    .biosMinimaRequerida(nombreBiosMinima)
                                    .condiciones(compat.getCondiciones())
                                    .mensajes(List.of("La versión de BIOS indicada no está registrada para esta revisión de placa; no es posible verificarla."))
                                    .build();
                        }

                        if (biosInstaladaOpt.get().getOrden() < biosMinima.getOrden()) {
                            return ValidarCambioResponseDto.builder()
                                    .esCompatible(true)
                                    .estadoCompatibilidad("CONDICIONAL")
                                    .requiereActualizacionBios(true)
                                    .biosMinimaRequerida(nombreBiosMinima)
                                    .condiciones(compat.getCondiciones())
                                    .mensajes(List.of("Compatible bajo condición: Requiere actualizar la BIOS instalada (" +
                                            request.getBiosInstalada() + ") a la versión mínima " + nombreBiosMinima + " o superior."))
                                    .build();
                        }
                    }

                    List<String> avisos = new ArrayList<>();
                    avisos.add("Combinación de CPU y Placa Madre VERIFICADA con soporte oficial del fabricante.");
                    if (nombreBiosMinima != null) {
                        avisos.add("Requiere versión de BIOS mínima: " + nombreBiosMinima + ".");
                    }

                    return ValidarCambioResponseDto.builder()
                            .esCompatible(true)
                            .estadoCompatibilidad("VERIFICADA")
                            .requiereActualizacionBios(false)
                            .biosMinimaRequerida(nombreBiosMinima)
                            .condiciones(compat.getCondiciones())
                            .mensajes(avisos)
                            .build();
                }
            }
        }

        // 5. Validar contra la matriz de compatibilidad por Socket, Generación y Chipset (rya.compatibilidad_cpu_chipset)
        String chipsetLimpio = limpiarChipset(chipsetPlaca);
        if (generacionCpu != null && chipsetLimpio != null) {
            Optional<CompatibilidadCpuChipset> ruleOpt = compatibilidadCpuChipsetRepository
                    .findFirstBySocketIgnoreCaseAndGeneracionIgnoreCaseAndChipsetIgnoreCase(socketCpu, generacionCpu, chipsetLimpio);

            if (ruleOpt.isPresent()) {
                CompatibilidadCpuChipset rule = ruleOpt.get();
                if (Boolean.TRUE.equals(rule.getRequiereActualizacion())) {
                    return ValidarCambioResponseDto.builder()
                            .esCompatible(true)
                            .estadoCompatibilidad("CONDICIONAL")
                            .requiereActualizacionBios(true)
                            .mensajes(List.of("Compatible bajo condición: El chipset " + (chipsetPlaca != null ? chipsetPlaca : chipsetLimpio) +
                                    " con socket " + socketCpu + " requiere actualización previa de BIOS para procesadores " + generacionCpu + "."))
                            .build();
                } else {
                    return ValidarCambioResponseDto.builder()
                            .esCompatible(true)
                            .estadoCompatibilidad("VERIFICADA")
                            .requiereActualizacionBios(false)
                            .mensajes(List.of("Combinación de CPU y Placa Madre VERIFICADA con compatibilidad nativa (Socket " + socketCpu +
                                    ", " + generacionCpu + ", Chipset " + (chipsetPlaca != null ? chipsetPlaca : chipsetLimpio) + ")."))
                            .build();
                }
            }
        }

        // 6. Si coinciden en socket físico pero no existe registro en rya.compatibilidad_cpu_placa ni en rya.compatibilidad_cpu_chipset
        return ValidarCambioResponseDto.builder()
                .esCompatible(false)
                .estadoCompatibilidad("POR_VERIFICAR")
                .mensajes(List.of("El socket físico coincide (" + socketCpu +
                        "), pero no existe registro de certificación en la tabla de compatibilidad y BIOS (rya.compatibilidad_cpu_placa). No se declara compatibilidad únicamente por socket."))
                .requiereActualizacionBios(false)
                .build();
    }

    private List<ResultadoRegla> validarReglasTecnicas(ValidarCambioRequestDto request) {
        List<ResultadoRegla> resultados = new ArrayList<>();
        Set<UUID> seleccionados = idsSeleccionados(request);

        Set<UUID> bloqueados = new LinkedHashSet<>(Optional.ofNullable(request.getComponentesBloqueados()).orElse(List.of()));
        Set<UUID> retirados = new LinkedHashSet<>(bloqueados);
        retirados.removeAll(seleccionados);
        if (!retirados.isEmpty()) {
            resultados.add(ResultadoRegla.incompatible("COMPONENTES_BLOQUEADOS",
                    "El cambio intenta retirar o sustituir componentes bloqueados: " + retirados + "."));
        }

        List<UUID> idsConAtributos = Stream.of(
                        request.getCpuId(), request.getPlacaId(), request.getRamId(), request.getGpuId(), request.getFuenteId(),
                        request.getGabineteId(), request.getRefrigeracionId())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, Map<String, ValorAtributoProducto>> atributos = cargarAtributos(idsConAtributos);

        validarRamYPlaca(request, atributos, resultados);
        validarRefrigeracion(request, atributos, resultados);
        validarGabinete(request, atributos, resultados);
        validarFuente(request, atributos, resultados);
        validarSalidaVideo(request, resultados);
        return resultados;
    }

    private void validarRamYPlaca(ValidarCambioRequestDto request,
                                  Map<UUID, Map<String, ValorAtributoProducto>> atributos,
                                  List<ResultadoRegla> resultados) {
        if (request.getRamId() == null || request.getPlacaId() == null) return;

        Map<String, ValorAtributoProducto> ram = atributos.getOrDefault(request.getRamId(), Map.of());
        Map<String, ValorAtributoProducto> placa = atributos.getOrDefault(request.getPlacaId(), Map.of());
        String tipoRam = texto(ram, "catalog_tech_ram_memory_type", "tipo_ram");
        String tipoPlaca = texto(placa, "catalog_tech_board_memory_type", "tipo_ram_placa");
        if (tipoRam == null || tipoPlaca == null) {
            resultados.add(ResultadoRegla.porVerificar("RAM_TIPO",
                    "Falta el tipo de memoria de la RAM o de la placa madre."));
        } else if (!igualTecnico(tipoRam, tipoPlaca)) {
            resultados.add(ResultadoRegla.incompatible("RAM_TIPO",
                    "La RAM usa " + tipoRam + " y la placa madre requiere " + tipoPlaca + "."));
        } else {
            resultados.add(ResultadoRegla.verificada("RAM_TIPO", "El tipo de RAM coincide con la placa madre."));
        }

        compararMaximo(ram, List.of("catalog_tech_ram_capacity_gb", "capacidad_ram_gb"),
                placa, List.of("catalog_tech_board_memory_max_gb", "capacidad_ram_max_placa_gb"),
                "RAM_CAPACIDAD", "La capacidad de RAM supera el máximo admitido por la placa.", resultados, false);
        compararMaximo(ram, List.of("catalog_tech_ram_modules", "modulos_ram"),
                placa, List.of("catalog_tech_board_memory_slots", "ranuras_ram_placa"),
                "RAM_RANURAS", "La cantidad de módulos RAM supera las ranuras de la placa.", resultados, false);
        compararMaximo(ram, List.of("catalog_tech_ram_speed_mts", "velocidad_ram_mt_s"),
                placa, List.of("catalog_tech_board_memory_oc_mts", "velocidad_ram_max_placa_mt_s"),
                "RAM_VELOCIDAD", "La RAM funcionará limitada a la velocidad máxima de la placa.", resultados, true);
    }

    private void validarRefrigeracion(ValidarCambioRequestDto request,
                                      Map<UUID, Map<String, ValorAtributoProducto>> atributos,
                                      List<ResultadoRegla> resultados) {
        if (request.getRefrigeracionId() == null) return;
        Map<String, ValorAtributoProducto> cooler = atributos.getOrDefault(request.getRefrigeracionId(), Map.of());

        if (request.getCpuId() != null) {
            EspecificacionCpu cpu = especificacionCpuRepository.findConProductoPorId(request.getCpuId()).orElse(null);
            Map<String, ValorAtributoProducto> attrsCpu = atributos.getOrDefault(request.getCpuId(), Map.of());
            String socketCpu = cpu != null && cpu.getSocket() != null ? cpu.getSocket() : texto(attrsCpu, "catalog_tech_cpu_socket", "socket_cpu");
            String sockets = texto(cooler, "catalog_tech_cooler_sockets", "sockets_cooler");
            if (socketCpu == null || sockets == null) {
                resultados.add(ResultadoRegla.porVerificar("COOLER_SOCKET",
                        "Falta la ficha de CPU o la lista de sockets compatibles del cooler."));
            } else if (!contieneOpcion(sockets, socketCpu)) {
                resultados.add(ResultadoRegla.incompatible("COOLER_SOCKET",
                        "El cooler no declara soporte para el socket " + socketCpu + "."));
            } else {
                resultados.add(ResultadoRegla.verificada("COOLER_SOCKET", "El cooler admite el socket del procesador."));
            }

            BigDecimal capacidad = numero(cooler, "catalog_tech_cooler_tdp_w", "capacidad_termica_cooler_w");
            BigDecimal potenciaMaxima = cpu != null && cpu.getPotenciaMaximaW() != null ? cpu.getPotenciaMaximaW()
                    : numero(attrsCpu, "catalog_tech_cpu_max_w", "catalog_tech_cpu_tdp_w", "potencia_maxima_w", "potencia_base_w");
            if (capacidad == null || potenciaMaxima == null) {
                resultados.add(ResultadoRegla.porVerificar("COOLER_TERMICA",
                        "Falta la potencia máxima documentada de la CPU o la capacidad térmica del cooler."));
            } else if (capacidad.compareTo(potenciaMaxima) < 0) {
                resultados.add(ResultadoRegla.incompatible("COOLER_TERMICA",
                        "La capacidad térmica del cooler es menor que la potencia máxima documentada de la CPU."));
            } else {
                resultados.add(ResultadoRegla.verificada("COOLER_TERMICA", "La capacidad térmica del cooler es suficiente."));
            }
        }
    }

    private void validarGabinete(ValidarCambioRequestDto request,
                                 Map<UUID, Map<String, ValorAtributoProducto>> atributos,
                                 List<ResultadoRegla> resultados) {
        if (request.getGabineteId() == null) return;
        Map<String, ValorAtributoProducto> gabinete = atributos.getOrDefault(request.getGabineteId(), Map.of());

        if (request.getPlacaId() != null) {
            validarOpcionIncluida(atributos.getOrDefault(request.getPlacaId(), Map.of()), List.of("catalog_tech_board_form_factor", "formato_placa"),
                    gabinete, List.of("catalog_tech_case_form_factor", "formatos_placa_gabinete"), "GABINETE_PLACA",
                    "El gabinete no admite el formato de la placa madre.", resultados);
        }
        if (request.getGpuId() != null) {
            Map<String, ValorAtributoProducto> gpu = atributos.getOrDefault(request.getGpuId(), Map.of());
            compararMaximo(gpu, List.of("catalog_tech_gpu_length_mm", "largo_gpu_mm"),
                    gabinete, List.of("catalog_tech_case_gpu_max_length_mm", "largo_gpu_max_mm"), "GABINETE_GPU_LARGO",
                    "La tarjeta gráfica excede el largo máximo del gabinete.", resultados, false);
            compararMaximo(gpu, List.of("catalog_tech_gpu_slots", "ancho_gpu_slots"),
                    gabinete, List.of("catalog_tech_case_gpu_max_slots", "ancho_gpu_max_slots"), "GABINETE_GPU_SLOTS",
                    "La tarjeta gráfica ocupa más slots de los admitidos por el gabinete.", resultados, false);
        }
        if (request.getFuenteId() != null) {
            Map<String, ValorAtributoProducto> fuente = atributos.getOrDefault(request.getFuenteId(), Map.of());
            validarOpcionIncluida(fuente, List.of("catalog_tech_psu_form_factor", "formato_psu"),
                    gabinete, List.of("catalog_tech_case_psu_form_factor", "formatos_psu_gabinete"), "GABINETE_PSU_FORMATO",
                    "El gabinete no admite el formato de la fuente de poder.", resultados);
            compararMaximo(fuente, List.of("catalog_tech_psu_length_mm", "largo_psu_mm"),
                    gabinete, List.of("catalog_tech_case_psu_max_length_mm", "largo_psu_max_mm"), "GABINETE_PSU_LARGO",
                    "La fuente de poder excede el largo máximo del gabinete.", resultados, false);
        }
        if (request.getRefrigeracionId() != null) {
            Map<String, ValorAtributoProducto> cooler = atributos.getOrDefault(request.getRefrigeracionId(), Map.of());
            BigDecimal altura = numero(cooler, "catalog_tech_cooler_height_mm", "altura_cooler_mm");
            if (altura != null) {
                compararMaximo(cooler, List.of("catalog_tech_cooler_height_mm", "altura_cooler_mm"),
                        gabinete, List.of("catalog_tech_case_cooler_max_height_mm", "altura_cooler_max_mm"), "GABINETE_COOLER_ALTURA",
                        "El disipador excede la altura máxima del gabinete.", resultados, false);
            }
            BigDecimal radiador = numero(cooler, "catalog_tech_cooler_radiator_mm", "tamano_radiador_mm");
            if (radiador != null) {
                String soportados = texto(gabinete, "catalog_tech_case_radiators", "radiadores_soportados_gabinete");
                if (soportados == null) {
                    resultados.add(ResultadoRegla.porVerificar("GABINETE_RADIADOR",
                            "El gabinete no tiene registrados los tamaños de radiador admitidos."));
                } else if (!contieneOpcion(soportados, radiador.stripTrailingZeros().toPlainString())) {
                    resultados.add(ResultadoRegla.incompatible("GABINETE_RADIADOR",
                            "El gabinete no admite un radiador de " + radiador.stripTrailingZeros().toPlainString() + " mm."));
                } else {
                    resultados.add(ResultadoRegla.verificada("GABINETE_RADIADOR", "El radiador cabe en el gabinete."));
                }
            }
        }
    }

    private void validarFuente(ValidarCambioRequestDto request,
                               Map<UUID, Map<String, ValorAtributoProducto>> atributos,
                               List<ResultadoRegla> resultados) {
        if (request.getFuenteId() == null) return;
        Map<String, ValorAtributoProducto> fuente = atributos.getOrDefault(request.getFuenteId(), Map.of());
        BigDecimal potenciaFuente = numero(fuente, "catalog_tech_psu_continuous_w", "potencia_psu_w");
        BigDecimal consumo = BigDecimal.ZERO;
        boolean consumoCompleto = true;

        if (request.getCpuId() != null) {
            EspecificacionCpu cpu = especificacionCpuRepository.findConProductoPorId(request.getCpuId()).orElse(null);
            if (cpu == null || (cpu.getPotenciaMaximaW() == null && cpu.getPotenciaBaseW() == null)) {
                BigDecimal cpuWatts = numero(atributos.getOrDefault(request.getCpuId(), Map.of()),
                        "catalog_tech_cpu_max_w", "catalog_tech_cpu_tdp_w", "potencia_maxima_w", "potencia_base_w");
                if (cpuWatts != null) consumo = consumo.add(cpuWatts);
                else consumoCompleto = false;
            } else {
                consumo = consumo.add(cpu.getPotenciaMaximaW() != null ? cpu.getPotenciaMaximaW() : cpu.getPotenciaBaseW());
            }
        }
        if (request.getGpuId() != null) {
            BigDecimal potenciaGpu = numero(atributos.getOrDefault(request.getGpuId(), Map.of()), "catalog_tech_gpu_board_w", "potencia_gpu_w");
            if (potenciaGpu == null) consumoCompleto = false;
            else consumo = consumo.add(potenciaGpu);
        }
        if (request.getRefrigeracionId() != null) {
            BigDecimal consumoCooler = numero(atributos.getOrDefault(request.getRefrigeracionId(), Map.of()), "consumo_cooler_w");
            if (consumoCooler != null) consumo = consumo.add(consumoCooler);
        }

        if (potenciaFuente == null || !consumoCompleto || consumo.signum() == 0) {
            resultados.add(ResultadoRegla.porVerificar("PSU_POTENCIA",
                    "Falta potencia documentada para calcular y verificar la fuente con margen seguro."));
        } else {
            int recomendada = calcularPsuEstandar(consumo.multiply(BigDecimal.valueOf(1.25))
                    .add(BigDecimal.valueOf(50)).setScale(0, RoundingMode.CEILING).intValue());
            if (potenciaFuente.compareTo(BigDecimal.valueOf(recomendada)) < 0) {
                resultados.add(ResultadoRegla.incompatible("PSU_POTENCIA",
                        "La fuente es de " + potenciaFuente.stripTrailingZeros().toPlainString()
                                + " W y la configuración requiere al menos " + recomendada + " W."));
            } else {
                resultados.add(ResultadoRegla.verificada("PSU_POTENCIA",
                        "La fuente cumple la potencia recomendada de " + recomendada + " W."));
            }
        }

        if (request.getGpuId() != null) {
            Map<String, ValorAtributoProducto> gpu = atributos.getOrDefault(request.getGpuId(), Map.of());
            String conector = texto(gpu, "catalog_tech_gpu_power_connectors", "conector_energia_gpu");
            BigDecimal cantidad = numero(gpu, "catalog_tech_gpu_connectors_count", "cantidad_conectores_gpu");
            if (conector != null && cantidad != null) {
                String codigoPsu = normalizarToken(conector).contains("12VHPWR") || normalizarToken(conector).contains("12V2X6")
                        ? "conectores_12vhpwr" : "conectores_pcie_8pin";
                BigDecimal disponibles = numero(fuente, codigoPsu);
                if (disponibles != null && disponibles.compareTo(cantidad) < 0) {
                    resultados.add(ResultadoRegla.incompatible("PSU_CONECTOR_GPU",
                            "La fuente no dispone de suficientes conectores para la GPU."));
                } else if (disponibles != null) {
                    resultados.add(ResultadoRegla.verificada("PSU_CONECTOR_GPU", "La fuente incluye los conectores requeridos por la GPU."));
                }
            }
        }
    }

    private void validarSalidaVideo(ValidarCambioRequestDto request, List<ResultadoRegla> resultados) {
        if (request.getCpuId() == null || (request.getGpuId() == null
                && request.getFuenteId() == null && request.getGabineteId() == null)) return;
        EspecificacionCpu cpu = especificacionCpuRepository.findConProductoPorId(request.getCpuId()).orElse(null);
        Boolean tieneIgpu = cpu != null ? cpu.getTieneGraficosIntegrados() : null;
        if (tieneIgpu == null) {
            Map<String, ValorAtributoProducto> cpuAttrs = cargarAtributos(List.of(request.getCpuId())).getOrDefault(request.getCpuId(), Map.of());
            ValorAtributoProducto v = cpuAttrs.get("catalog_tech_cpu_igpu");
            if (v == null) v = cpuAttrs.get("graficos_integrados_cpu");
            if (v != null && v.getValorBooleano() != null) tieneIgpu = v.getValorBooleano();
        }
        if (tieneIgpu == null) {
            resultados.add(ResultadoRegla.porVerificar("SALIDA_VIDEO", "No se conoce si el procesador integra gráficos."));
        } else if (!tieneIgpu && request.getGpuId() == null) {
            resultados.add(ResultadoRegla.condicional("SALIDA_VIDEO",
                    "El procesador no tiene gráficos integrados; debes añadir una tarjeta gráfica dedicada."));
        }
    }

    private ValidarCambioResponseDto combinarResultados(ValidarCambioResponseDto plataforma,
                                                         List<ResultadoRegla> reglas) {
        String estado = plataforma.getEstadoCompatibilidad();
        List<String> mensajes = new ArrayList<>(Optional.ofNullable(plataforma.getMensajes()).orElse(List.of()));
        List<Map<String, String>> detalleReglas = new ArrayList<>();
        for (ResultadoRegla regla : reglas) {
            if ("INCOMPATIBLE".equalsIgnoreCase(regla.estado())) {
                estado = "INCOMPATIBLE";
            } else if ("CONDICIONAL".equalsIgnoreCase(regla.estado()) && "VERIFICADA".equalsIgnoreCase(estado)) {
                estado = "CONDICIONAL";
            }
            mensajes.add(regla.mensaje());
            detalleReglas.add(Map.of("codigo", regla.codigo(), "estado", regla.estado(), "mensaje", regla.mensaje()));
        }
        Map<String, Object> detalles = new LinkedHashMap<>(Optional.ofNullable(plataforma.getDetallesTecnicos()).orElse(Map.of()));
        detalles.put("reglas", detalleReglas);
        boolean compatible = "VERIFICADA".equals(estado) || "CONDICIONAL".equals(estado);
        return ValidarCambioResponseDto.builder()
                .esCompatible(compatible)
                .estadoCompatibilidad(estado)
                .mensajes(mensajes)
                .requiereActualizacionBios(plataforma.getRequiereActualizacionBios())
                .biosMinimaRequerida(plataforma.getBiosMinimaRequerida())
                .condiciones(plataforma.getCondiciones())
                .detallesTecnicos(detalles)
                .build();
    }

    private Map<UUID, Map<String, ValorAtributoProducto>> cargarAtributos(List<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        Map<UUID, Map<String, ValorAtributoProducto>> resultado = new HashMap<>();
        for (ValorAtributoProducto valor : valorAtributoProductoRepository.findConAtributoPorProductoIds(ids)) {
            resultado.computeIfAbsent(valor.getProducto().getId(), ignored -> new HashMap<>())
                    .put(valor.getAtributo().getCodigo().toLowerCase(Locale.ROOT), valor);
        }
        return resultado;
    }

    private Set<UUID> idsSeleccionados(ValidarCambioRequestDto request) {
        Set<UUID> ids = new LinkedHashSet<>(Optional.ofNullable(request.getComponentesActuales()).orElse(List.of()));
        Stream.of(request.getCpuId(), request.getPlacaId(), request.getRamId(), request.getGpuId(), request.getFuenteId(),
                        request.getGabineteId(), request.getRefrigeracionId())
                .filter(Objects::nonNull).forEach(ids::add);
        return ids;
    }

    private String texto(Map<String, ValorAtributoProducto> atributos, String... codigos) {
        for (String codigo : codigos) {
            ValorAtributoProducto valor = atributos.get(codigo.toLowerCase(Locale.ROOT));
            if (valor != null && valor.getValorTexto() != null && !valor.getValorTexto().isBlank()) {
                return valor.getValorTexto().trim();
            }
        }
        return null;
    }

    private BigDecimal numero(Map<String, ValorAtributoProducto> atributos, String... codigos) {
        for (String codigo : codigos) {
            ValorAtributoProducto valor = atributos.get(codigo.toLowerCase(Locale.ROOT));
            if (valor != null && valor.getValorNumero() != null) {
                return valor.getValorNumero();
            }
        }
        return null;
    }

    private void compararMaximo(Map<String, ValorAtributoProducto> componente, List<String> codigosValor,
                                Map<String, ValorAtributoProducto> limite, List<String> codigosLimite,
                                String codigoRegla, String mensajeFallo, List<ResultadoRegla> resultados,
                                boolean condicionalSiSupera) {
        BigDecimal valor = numero(componente, codigosValor.toArray(new String[0]));
        BigDecimal maximo = numero(limite, codigosLimite.toArray(new String[0]));
        if (valor == null || maximo == null) {
            resultados.add(ResultadoRegla.porVerificar(codigoRegla,
                    "Faltan datos técnicos para verificar " + codigoRegla.toLowerCase(Locale.ROOT).replace('_', ' ') + "."));
        } else if (valor.compareTo(maximo) > 0) {
            resultados.add(condicionalSiSupera ? ResultadoRegla.condicional(codigoRegla, mensajeFallo)
                    : ResultadoRegla.incompatible(codigoRegla, mensajeFallo));
        } else {
            resultados.add(ResultadoRegla.verificada(codigoRegla, "La restricción " + codigoRegla + " se cumple."));
        }
    }

    private void compararMaximo(Map<String, ValorAtributoProducto> componente, String codigoValor,
                                Map<String, ValorAtributoProducto> limite, String codigoLimite,
                                String codigoRegla, String mensajeFallo, List<ResultadoRegla> resultados,
                                boolean condicionalSiSupera) {
        compararMaximo(componente, List.of(codigoValor), limite, List.of(codigoLimite), codigoRegla, mensajeFallo, resultados, condicionalSiSupera);
    }

    private void validarOpcionIncluida(Map<String, ValorAtributoProducto> componente, List<String> codigosValor,
                                       Map<String, ValorAtributoProducto> contenedor, List<String> codigosOpciones,
                                       String codigoRegla, String mensajeFallo, List<ResultadoRegla> resultados) {
        String valor = texto(componente, codigosValor.toArray(new String[0]));
        String opciones = texto(contenedor, codigosOpciones.toArray(new String[0]));
        if (valor == null || opciones == null) {
            resultados.add(ResultadoRegla.porVerificar(codigoRegla, "Faltan datos técnicos para verificar " + codigoRegla + "."));
        } else if (!contieneOpcion(opciones, valor)) {
            resultados.add(ResultadoRegla.incompatible(codigoRegla, mensajeFallo));
        } else {
            resultados.add(ResultadoRegla.verificada(codigoRegla, "La restricción " + codigoRegla + " se cumple."));
        }
    }

    private void validarOpcionIncluida(Map<String, ValorAtributoProducto> componente, String codigoValor,
                                       Map<String, ValorAtributoProducto> contenedor, String codigoOpciones,
                                       String codigoRegla, String mensajeFallo, List<ResultadoRegla> resultados) {
        validarOpcionIncluida(componente, List.of(codigoValor), contenedor, List.of(codigoOpciones), codigoRegla, mensajeFallo, resultados);
    }

    private boolean contieneOpcion(String opciones, String valor) {
        String objetivo = normalizarToken(valor);
        return Arrays.stream(Optional.ofNullable(opciones).orElse("").split("[,;/|\\n]+"))
                .map(this::normalizarToken)
                .anyMatch(opcion -> opcion.equals(objetivo) || opcion.startsWith(objetivo));
    }

    private boolean igualTecnico(String izquierda, String derecha) {
        return normalizarToken(izquierda).equals(normalizarToken(derecha));
    }

    private String normalizarToken(String valor) {
        return Optional.ofNullable(valor).orElse("").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private record ResultadoRegla(String codigo, String estado, String mensaje) {
        static ResultadoRegla verificada(String codigo, String mensaje) {
            return new ResultadoRegla(codigo, "VERIFICADA", mensaje);
        }
        static ResultadoRegla condicional(String codigo, String mensaje) {
            return new ResultadoRegla(codigo, "CONDICIONAL", mensaje);
        }
        static ResultadoRegla incompatible(String codigo, String mensaje) {
            return new ResultadoRegla(codigo, "INCOMPATIBLE", mensaje);
        }
        static ResultadoRegla porVerificar(String codigo, String mensaje) {
            return new ResultadoRegla(codigo, "POR_VERIFICAR", mensaje);
        }
    }

    @Override
    public CalcularConfiguracionResponseDto calcularConfiguracion(CalcularConfiguracionRequestDto request) {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new BadRequestException("Debe proporcionar al menos un componente en la configuración");
        }

        List<UUID> productIds = request.getItems().stream().map(ItemConfiguracionDto::getProductoId).toList();
        List<Producto> productos = productoRepository.findAllById(productIds);
        Map<UUID, Producto> productoMap = new HashMap<>();
        for (Producto p : productos) {
            productoMap.put(p.getId(), p);
        }

        // Stock disponible
        List<Object[]> stockRows = existenciaRepository.findStockDisponiblePorProductoIds(productIds);
        Map<UUID, Integer> stockMap = new HashMap<>();
        for (Object[] r : stockRows) {
            stockMap.put((UUID) r[0], ((Long) r[1]).intValue());
        }

        // Cargar atributos de consumo en lote
        Map<UUID, List<ValorAtributoProducto>> attrsPorProducto = new HashMap<>();
        for (ValorAtributoProducto v : valorAtributoProductoRepository.findConAtributoPorProductoIds(productIds)) {
            attrsPorProducto.computeIfAbsent(v.getProducto().getId(), ignored -> new ArrayList<>()).add(v);
        }

        BigDecimal precioTotal = BigDecimal.ZERO;
        BigDecimal consumoTotalW = BigDecimal.ZERO;
        boolean todosDisponibles = true;
        boolean datosConsumoIncompletos = false;
        List<ItemCalculadoDto> itemsCalculados = new ArrayList<>();
        List<String> notas = new ArrayList<>();

        for (ItemConfiguracionDto itemReq : request.getItems()) {
            Producto prod = productoMap.get(itemReq.getProductoId());
            if (prod == null || Boolean.TRUE.equals(prod.getIsDeleted())) {
                throw new ResourceNotFoundException("El producto con ID " + itemReq.getProductoId() + " no existe o fue eliminado");
            }
            if (!Boolean.TRUE.equals(prod.getActivo()) || !Boolean.TRUE.equals(prod.getVendible())) {
                throw new BadRequestException("El producto " + prod.getNombre() + " (" + prod.getSku() + ") no se encuentra activo para la venta");
            }

            int stock = stockMap.getOrDefault(prod.getId(), 0);
            boolean suficienteStock = stock >= itemReq.getCantidad();
            if (!suficienteStock) {
                todosDisponibles = false;
                notas.add("Stock insuficiente para: " + prod.getNombre() + " (Solicitado: " + itemReq.getCantidad() + ", Disponible: " + stock + ")");
            }

            BigDecimal subtotal = prod.getPrecioVenta().multiply(BigDecimal.valueOf(itemReq.getCantidad()));
            precioTotal = precioTotal.add(subtotal);

            // Obtener potencia estimada
            BigDecimal potenciaEstimada = BigDecimal.ZERO;
            Optional<EspecificacionCpu> cpuOpt = especificacionCpuRepository.findById(prod.getId());
            if (cpuOpt.isEmpty()) {
                cpuOpt = especificacionCpuRepository.findConProductoPorId(prod.getId());
            }

            if (cpuOpt.isPresent()) {
                EspecificacionCpu cpu = cpuOpt.get();
                potenciaEstimada = cpu.getPotenciaMaximaW() != null ? cpu.getPotenciaMaximaW() : cpu.getPotenciaBaseW();
            } else {
                // Buscar atributos técnicos de consumo/potencia (TDP, Consumo W, board_w, etc.)
                List<ValorAtributoProducto> attrs = attrsPorProducto.getOrDefault(prod.getId(), List.of());
                if (attrs.isEmpty()) {
                    attrs = valorAtributoProductoRepository.findConAtributoPorProductoId(prod.getId());
                }
                boolean encontrado = false;
                for (ValorAtributoProducto v : attrs) {
                    String cod = v.getAtributo().getCodigo().toLowerCase(Locale.ROOT);
                    if ((cod.contains("tdp") || cod.contains("potencia") || cod.contains("consumo")
                            || cod.contains("board_w") || cod.contains("watts") || cod.endsWith("_w"))
                            && v.getValorNumero() != null) {
                        potenciaEstimada = v.getValorNumero();
                        encontrado = true;
                        break;
                    }
                }
                if (!encontrado) {
                    datosConsumoIncompletos = true;
                }
            }

            consumoTotalW = consumoTotalW.add(potenciaEstimada.multiply(BigDecimal.valueOf(itemReq.getCantidad())));

            itemsCalculados.add(ItemCalculadoDto.builder()
                    .productoId(prod.getId())
                    .nombre(prod.getNombre())
                    .sku(prod.getSku())
                    .cantidad(itemReq.getCantidad())
                    .precioUnitario(prod.getPrecioVenta())
                    .subtotal(subtotal)
                    .stockDisponible(stock)
                    .stockSuficiente(suficienteStock)
                    .potenciaEstimadaW(potenciaEstimada)
                    .build());
        }

        if (datosConsumoIncompletos) {
            notas.add("Advertencia: No todos los componentes poseen especificaciones de potencia/TDP en la base de datos. El consumo estimado no contempla los componentes sin ficha de potencia.");
        }

        // Recomendar fuente de poder
        Integer fuenteRecomendada = null;
        if (consumoTotalW.compareTo(BigDecimal.ZERO) > 0) {
            // Factor de seguridad 1.25x + margen base de 50W
            BigDecimal consumoConMargen = consumoTotalW.multiply(BigDecimal.valueOf(1.25)).add(BigDecimal.valueOf(50));
            int psuMin = consumoConMargen.setScale(0, RoundingMode.CEILING).intValue();
            fuenteRecomendada = calcularPsuEstandar(psuMin);
        }

        return CalcularConfiguracionResponseDto.builder()
                .items(itemsCalculados)
                .precioTotal(precioTotal)
                .consumoEstimadoW(consumoTotalW)
                .fuenteRecomendadaW(fuenteRecomendada)
                .todosDisponibles(todosDisponibles)
                .datosConsumoIncompletos(datosConsumoIncompletos)
                .notas(notas)
                .build();
    }

    private Integer calcularPsuEstandar(int w) {
        int[] estandares = {450, 500, 550, 600, 650, 700, 750, 850, 1000, 1200, 1600};
        for (int psu : estandares) {
            if (psu >= w) {
                return psu;
            }
        }
        return w;
    }

    private List<ProductoResumenDto> mapearProductosConStockEImagenes(List<Producto> productos) {
        if (productos.isEmpty()) {
            return List.of();
        }

        List<UUID> ids = productos.stream().map(Producto::getId).toList();

        List<ImagenProducto> imagenes = imagenProductoRepository.findByProductoIdInAndIsDeletedFalseOrderByPosicionAsc(ids);
        Map<UUID, String> imagenMap = new HashMap<>();
        for (ImagenProducto img : imagenes) {
            UUID pId = img.getProducto().getId();
            if (!imagenMap.containsKey(pId) || Boolean.TRUE.equals(img.getPrincipal())) {
                imagenMap.put(pId, img.getUrl());
            }
        }

        List<Object[]> stockRows = existenciaRepository.findStockDisponiblePorProductoIds(ids);
        Map<UUID, Integer> stockMap = new HashMap<>();
        for (Object[] r : stockRows) {
            stockMap.put((UUID) r[0], ((Long) r[1]).intValue());
        }

        return productos.stream()
                .map(p -> productoMapper.toResumenDto(p, imagenMap.get(p.getId()), stockMap.getOrDefault(p.getId(), 0)))
                .toList();
    }

    @Override
    public RecomendarPcResponseDto recomendarConfiguracion(RecomendarPcRequestDto request) {
        if (request == null) {
            throw new BadRequestException("La solicitud de recomendación no puede ser nula");
        }
        if (request.getProfileId() == null) {
            throw new BadRequestException("El perfil de asesoría es obligatorio");
        }
        if (request.getBudget() == null || request.getBudget().compareTo(new BigDecimal("500.00")) < 0) {
            throw new BadRequestException("El presupuesto debe ser de al menos S/ 500.00");
        }

        BigDecimal budget = request.getBudget();

        PerfilAsesoria perfil = perfilAsesoriaRepository.findById(request.getProfileId())
                .filter(p -> !Boolean.TRUE.equals(p.getIsDeleted()))
                .orElseThrow(() -> new ResourceNotFoundException("Perfil de asesoría no encontrado: " + request.getProfileId()));

        PcBuilderInicialDto inicial = obtenerDatosIniciales();
        List<TipoComponenteResumenDto> disponibles = inicial.getComponentTypes();

        String codeCpu = resolverCodigoTipoParaRol("CPU", disponibles);
        String codeBoard = resolverCodigoTipoParaRol("BOARD", disponibles);
        String codeRam = resolverCodigoTipoParaRol("RAM", disponibles);
        String codeStorage = resolverCodigoTipoParaRol("STORAGE", disponibles);
        String codeGpu = resolverCodigoTipoParaRol("GPU", disponibles);
        String codePsu = resolverCodigoTipoParaRol("PSU", disponibles);
        String codeCase = resolverCodigoTipoParaRol("CASE", disponibles);
        String codeCooler = resolverCodigoTipoParaRol("COOLER", disponibles);

        List<ProductoResumenDto> cpus = obtenerProductosVendiblesConStock(codeCpu);
        List<ProductoResumenDto> placas = obtenerProductosVendiblesConStock(codeBoard);
        List<ProductoResumenDto> rams = obtenerProductosVendiblesConStock(codeRam);
        List<ProductoResumenDto> storages = obtenerProductosVendiblesConStock(codeStorage);
        List<ProductoResumenDto> gpus = obtenerProductosVendiblesConStock(codeGpu);
        List<ProductoResumenDto> psus = obtenerProductosVendiblesConStock(codePsu);
        List<ProductoResumenDto> cases = obtenerProductosVendiblesConStock(codeCase);
        List<ProductoResumenDto> coolers = obtenerProductosVendiblesConStock(codeCooler);

        if (cpus.isEmpty() || placas.isEmpty() || rams.isEmpty() || storages.isEmpty() || psus.isEmpty() || cases.isEmpty()) {
            throw new BadRequestException("El catálogo actual no cuenta con suficientes componentes en inventario para armar una PC completa.");
        }

        List<UUID> todosIds = Stream.of(cpus, placas, rams, storages, gpus, psus, cases, coolers)
                .flatMap(Collection::stream)
                .map(ProductoResumenDto::getId)
                .distinct()
                .toList();

        Map<UUID, Map<String, ValorAtributoProducto>> atributosMap = cargarAtributos(todosIds);
        Map<UUID, EspecificacionCpu> cpusMap = new HashMap<>();
        for (ProductoResumenDto c : cpus) {
            especificacionCpuRepository.findConProductoPorId(c.getId()).ifPresent(spec -> cpusMap.put(c.getId(), spec));
        }

        Map<UUID, RevisionPlaca> placasMap = new HashMap<>();
        for (ProductoResumenDto b : placas) {
            List<RevisionPlaca> revs = revisionPlacaRepository.findByProductoId(b.getId());
            if (!revs.isEmpty()) {
                placasMap.put(b.getId(), revs.get(0));
            }
        }

        boolean exigeGpuDedicada = Boolean.TRUE.equals(perfil.getRequiereGpuDedicada())
                || (perfil.getPrioridadGpu() != null && perfil.getPrioridadGpu() >= 6)
                || budget.compareTo(new BigDecimal("2800.00")) >= 0;

        List<ProductoResumenDto> cpusFiltradas = new ArrayList<>();
        for (ProductoResumenDto c : cpus) {
            boolean tieneIgpu = tieneGraficosIntegrados(c.getId(), cpusMap, atributosMap);
            if (exigeGpuDedicada || tieneIgpu) {
                cpusFiltradas.add(c);
            }
        }
        if (cpusFiltradas.isEmpty()) {
            cpusFiltradas = new ArrayList<>(cpus);
        }

        // Ordenar GPUs por precio descendente para evaluar configuraciones de alto rendimiento
        List<ProductoResumenDto> gpusOrdenadas = new ArrayList<>(gpus);
        gpusOrdenadas.sort(Comparator.comparing(ProductoResumenDto::getPrecioBase, Comparator.reverseOrder()));

        List<ProductoResumenDto> storagesOrdenados = new ArrayList<>(storages);
        storagesOrdenados.sort(Comparator.comparing(ProductoResumenDto::getPrecioBase, Comparator.reverseOrder()));

        EnsambleCandidato mejorEnsamble = null;

        for (ProductoResumenDto cpuCand : cpusFiltradas) {
            String socketCpu = obtenerSocketCpu(cpuCand.getId(), cpusMap, atributosMap);
            boolean cpuCoolerIncluded = cpuIncluyeDisipador(cpuCand.getId(), atributosMap);
            String cpuRamType = obtenerTipoRamCpu(cpuCand.getId(), atributosMap);
            boolean tieneIgpu = tieneGraficosIntegrados(cpuCand.getId(), cpusMap, atributosMap);

            List<ProductoResumenDto> placasCompatibles = new ArrayList<>();
            for (ProductoResumenDto placaCand : placas) {
                String socketPlaca = obtenerSocketPlaca(placaCand.getId(), placasMap, atributosMap);
                if (socketCpu != null && socketPlaca != null && !igualTecnico(socketCpu, socketPlaca)) {
                    continue;
                }
                placasCompatibles.add(placaCand);
            }
            if (placasCompatibles.isEmpty()) continue;

            // Evaluar opciones de GPU para esta CPU
            List<ProductoResumenDto> gpusCandidatas = new ArrayList<>(gpusOrdenadas);
            if (!exigeGpuDedicada && tieneIgpu) {
                gpusCandidatas.add(null); // Opción de usar gráficos integrados
            }

            for (ProductoResumenDto placaCand : placasCompatibles) {
                String boardRamType = obtenerTipoRamPlaca(placaCand.getId(), atributosMap);
                String boardFormFactor = obtenerFormFactorPlaca(placaCand.getId(), atributosMap);

                List<ProductoResumenDto> ramsCompatibles = new ArrayList<>();
                for (ProductoResumenDto ramCand : rams) {
                    String ramType = obtenerTipoRam(ramCand.getId(), atributosMap);
                    if (boardRamType != null && ramType != null && !igualTecnico(boardRamType, ramType)) {
                        continue;
                    }
                    if (cpuRamType != null && ramType != null && !contieneOpcion(cpuRamType, ramType)) {
                        continue;
                    }
                    int capRam = obtenerCapacidadRamGb(ramCand.getId(), atributosMap);
                    if (perfil.getRamMinimaGb() != null && capRam > 0 && capRam < perfil.getRamMinimaGb()) {
                        continue;
                    }
                    ramsCompatibles.add(ramCand);
                }
                if (ramsCompatibles.isEmpty()) continue;
                ramsCompatibles.sort(Comparator.comparing(ProductoResumenDto::getPrecioBase, Comparator.reverseOrder()));

                ProductoResumenDto coolerElegido = null;
                if (!cpuCoolerIncluded) {
                    List<ProductoResumenDto> coolersCompatibles = new ArrayList<>();
                    for (ProductoResumenDto cool : coolers) {
                        String socketsCooler = texto(atributosMap.getOrDefault(cool.getId(), Map.of()), "catalog_tech_cooler_sockets", "sockets_cooler");
                        if (socketCpu == null || socketsCooler == null || contieneOpcion(socketsCooler, socketCpu)) {
                            coolersCompatibles.add(cool);
                        }
                    }
                    if (!coolersCompatibles.isEmpty()) {
                        coolerElegido = coolersCompatibles.get(0);
                    } else if (!coolers.isEmpty()) {
                        coolerElegido = coolers.get(0);
                    }
                }

                for (ProductoResumenDto gpuCand : gpusCandidatas) {
                    if (gpuCand == null && (exigeGpuDedicada || !tieneIgpu)) {
                        continue;
                    }

                    // Buscar case compatible
                    List<ProductoResumenDto> casesCompatibles = new ArrayList<>();
                    for (ProductoResumenDto caseCand : cases) {
                        String caseForm = texto(atributosMap.getOrDefault(caseCand.getId(), Map.of()), "formatos_placa_gabinete");
                        if (caseForm == null) caseForm = texto(atributosMap.getOrDefault(caseCand.getId(), Map.of()), "catalog_tech_case_form_factor");
                        if (boardFormFactor != null && caseForm != null && !contieneOpcion(caseForm, boardFormFactor)) {
                            continue;
                        }
                        if (gpuCand != null) {
                            BigDecimal gpuLen = numero(atributosMap.getOrDefault(gpuCand.getId(), Map.of()), "catalog_tech_gpu_length_mm", "largo_gpu_mm");
                            BigDecimal maxGpuCase = numero(atributosMap.getOrDefault(caseCand.getId(), Map.of()), "catalog_tech_case_gpu_max_length_mm", "largo_gpu_max_mm");
                            if (gpuLen != null && maxGpuCase != null && gpuLen.compareTo(maxGpuCase) > 0) {
                                continue;
                            }
                        }
                        casesCompatibles.add(caseCand);
                    }
                    if (casesCompatibles.isEmpty()) casesCompatibles = new ArrayList<>(cases);
                    casesCompatibles.sort(Comparator.comparing(ProductoResumenDto::getPrecioBase));
                    ProductoResumenDto caseElegido = casesCompatibles.get(0);

                    // Calcular requerimiento de fuente de poder
                    BigDecimal consumoBase = BigDecimal.valueOf(70);
                    BigDecimal cpuWatts = obtenerPotenciaCpuW(cpuCand.getId(), cpusMap, atributosMap);
                    consumoBase = consumoBase.add(cpuWatts != null ? cpuWatts : BigDecimal.valueOf(65));
                    if (gpuCand != null) {
                        BigDecimal gpuWatts = numero(atributosMap.getOrDefault(gpuCand.getId(), Map.of()), "catalog_tech_gpu_board_w", "potencia_gpu_w");
                        consumoBase = consumoBase.add(gpuWatts != null ? gpuWatts : BigDecimal.valueOf(150));
                    }
                    int psuMinRequerida = consumoBase.multiply(BigDecimal.valueOf(1.25)).add(BigDecimal.valueOf(50))
                            .setScale(0, RoundingMode.CEILING).intValue();

                    List<ProductoResumenDto> psusCompatibles = new ArrayList<>();
                    for (ProductoResumenDto psuCand : psus) {
                        BigDecimal potencia = numero(atributosMap.getOrDefault(psuCand.getId(), Map.of()), "catalog_tech_psu_continuous_w", "potencia_psu_w");
                        if (potencia != null && potencia.intValue() >= psuMinRequerida) {
                            psusCompatibles.add(psuCand);
                        }
                    }
                    if (psusCompatibles.isEmpty()) psusCompatibles = new ArrayList<>(psus);
                    psusCompatibles.sort(Comparator.comparing(ProductoResumenDto::getPrecioBase));
                    ProductoResumenDto psuElegida = psusCompatibles.get(0);

                    // Probar combinaciones de RAM y Almacenamiento
                    for (ProductoResumenDto ramCand : ramsCompatibles) {
                        for (ProductoResumenDto storageCand : storagesOrdenados) {
                            EnsambleCandidato ensamble = new EnsambleCandidato(
                                    cpuCand, placaCand, ramCand, storageCand, gpuCand, psuElegida, caseElegido, coolerElegido
                            );

                            if (esMejorEnsamble(ensamble, mejorEnsamble, budget)) {
                                mejorEnsamble = ensamble;
                            }
                        }
                    }
                }
            }
        }

        if (mejorEnsamble == null) {
            throw new BadRequestException("No fue posible armar una configuración compatible con los componentes disponibles.");
        }

        List<ItemConfiguracionDto> itemsCalculo = new ArrayList<>();
        itemsCalculo.add(ItemConfiguracionDto.builder().productoId(mejorEnsamble.cpu.getId()).cantidad(1).build());
        itemsCalculo.add(ItemConfiguracionDto.builder().productoId(mejorEnsamble.placa.getId()).cantidad(1).build());
        itemsCalculo.add(ItemConfiguracionDto.builder().productoId(mejorEnsamble.ram.getId()).cantidad(1).build());
        itemsCalculo.add(ItemConfiguracionDto.builder().productoId(mejorEnsamble.storage.getId()).cantidad(1).build());
        itemsCalculo.add(ItemConfiguracionDto.builder().productoId(mejorEnsamble.psu.getId()).cantidad(1).build());
        itemsCalculo.add(ItemConfiguracionDto.builder().productoId(mejorEnsamble.casePc.getId()).cantidad(1).build());
        if (mejorEnsamble.gpu != null) {
            itemsCalculo.add(ItemConfiguracionDto.builder().productoId(mejorEnsamble.gpu.getId()).cantidad(1).build());
        }
        if (mejorEnsamble.cooler != null) {
            itemsCalculo.add(ItemConfiguracionDto.builder().productoId(mejorEnsamble.cooler.getId()).cantidad(1).build());
        }

        CalcularConfiguracionResponseDto calculo = calcularConfiguracion(CalcularConfiguracionRequestDto.builder()
                .items(itemsCalculo)
                .build());

        ValidarCambioResponseDto validacion = validarCambio(ValidarCambioRequestDto.builder()
                .cpuId(mejorEnsamble.cpu.getId())
                .placaId(mejorEnsamble.placa.getId())
                .ramId(mejorEnsamble.ram.getId())
                .gpuId(mejorEnsamble.gpu != null ? mejorEnsamble.gpu.getId() : null)
                .fuenteId(mejorEnsamble.psu.getId())
                .gabineteId(mejorEnsamble.casePc.getId())
                .refrigeracionId(mejorEnsamble.cooler != null ? mejorEnsamble.cooler.getId() : null)
                .componentesActuales(itemsCalculo.stream().map(ItemConfiguracionDto::getProductoId).toList())
                .build());

        List<ComponenteOpcionDto> componentesResultado = new ArrayList<>();
        componentesResultado.add(construirComponenteOpcion(mejorEnsamble.cpu, codeCpu));
        componentesResultado.add(construirComponenteOpcion(mejorEnsamble.placa, codeBoard));
        componentesResultado.add(construirComponenteOpcion(mejorEnsamble.ram, codeRam));
        componentesResultado.add(construirComponenteOpcion(mejorEnsamble.storage, codeStorage));
        if (mejorEnsamble.gpu != null) {
            componentesResultado.add(construirComponenteOpcion(mejorEnsamble.gpu, codeGpu));
        }
        componentesResultado.add(construirComponenteOpcion(mejorEnsamble.psu, codePsu));
        componentesResultado.add(construirComponenteOpcion(mejorEnsamble.casePc, codeCase));
        if (mejorEnsamble.cooler != null) {
            componentesResultado.add(construirComponenteOpcion(mejorEnsamble.cooler, codeCooler));
        }

        List<String> avisos = new ArrayList<>();
        if (Boolean.TRUE.equals(validacion.getRequiereActualizacionBios())) {
            avisos.add("La placa madre requiere actualización previa de BIOS (" + validacion.getBiosMinimaRequerida() + ") para esta generación de procesador.");
        }
        if (calculo.getPrecioTotal().compareTo(budget) > 0) {
            avisos.add("El presupuesto ingresado (S/ " + budget + ") es ligeramente superado para garantizar componentes de calidad compatibles (S/ " + calculo.getPrecioTotal() + ").");
        }
        if (calculo.getNotas() != null) {
            avisos.addAll(calculo.getNotas());
        }

        int consumoWatts = calculo.getConsumoEstimadoW() != null ? calculo.getConsumoEstimadoW().setScale(0, RoundingMode.HALF_UP).intValue() : 300;
        int psuWatts = calculo.getFuenteRecomendadaW() != null ? calculo.getFuenteRecomendadaW() : 600;

        return RecomendarPcResponseDto.builder()
                .components(componentesResultado)
                .total(calculo.getPrecioTotal())
                .currency("PEN")
                .powerDrawWatts(consumoWatts)
                .recommendedPsuWatts(psuWatts)
                .compatible(validacion.getEsCompatible())
                .warnings(avisos)
                .budget(budget)
                .build();
    }

    private ComponenteOpcionDto construirComponenteOpcion(ProductoResumenDto prod, String code) {
        return ComponenteOpcionDto.builder()
                .id(prod.getId())
                .componentTypeCode(code)
                .name(prod.getNombre())
                .brand(prod.getMarca())
                .slug(prod.getSlug())
                .summary(prod.getSku())
                .price(prod.getPrecioBase())
                .currency("PEN")
                .stock(prod.getStockDisponible())
                .imageUrl(prod.getImagenPrincipal())
                .imageUrls(prod.getImagenPrincipal() != null ? List.of(prod.getImagenPrincipal()) : List.of())
                .compatibility("VERIFIED")
                .build();
    }

    private List<ProductoResumenDto> obtenerProductosVendiblesConStock(String code) {
        if (code == null) return List.of();
        ComponenteTipoDto tipoDto = listarComponentesPorTipo(code);
        if (tipoDto == null || tipoDto.getProductos() == null) return List.of();
        return tipoDto.getProductos().stream()
                .filter(p -> p.getStockDisponible() != null && p.getStockDisponible() > 0)
                .toList();
    }

    private String resolverCodigoTipoParaRol(String role, List<TipoComponenteResumenDto> disponibles) {
        for (TipoComponenteResumenDto t : disponibles) {
            String norm = normalizarToken(t.getCode() + " " + t.getName());
            switch (role.toUpperCase(Locale.ROOT)) {
                case "CPU":
                    if (norm.contains("CPU") || norm.contains("PROCESADOR")) return t.getCode();
                    break;
                case "BOARD":
                    if (norm.contains("PLACA") || norm.contains("MOTHERBOARD") || norm.contains("BOARD") || norm.contains("MB")) return t.getCode();
                    break;
                case "RAM":
                    if (norm.contains("RAM") || norm.contains("MEMORIA")) return t.getCode();
                    break;
                case "STORAGE":
                    if (norm.contains("STORAGE") || norm.contains("ALMACENAMIENTO") || norm.contains("SSD") || norm.contains("DISCO")) return t.getCode();
                    break;
                case "GPU":
                    if (norm.contains("GPU") || norm.contains("GRAFICA") || norm.contains("VIDEO")) return t.getCode();
                    break;
                case "PSU":
                    if (norm.contains("PSU") || norm.contains("FUENTE") || norm.contains("PODER")) return t.getCode();
                    break;
                case "CASE":
                    if (norm.contains("CASE") || norm.contains("GABINETE")) return t.getCode();
                    break;
                case "COOLER":
                    if (norm.contains("COOLER") || norm.contains("REFRIGERACION") || norm.contains("DISIPADOR")) return t.getCode();
                    break;
            }
        }
        return role;
    }

    private String obtenerSocketCpu(UUID id, Map<UUID, EspecificacionCpu> cpusMap, Map<UUID, Map<String, ValorAtributoProducto>> attrs) {
        EspecificacionCpu spec = cpusMap.get(id);
        if (spec != null && spec.getSocket() != null) return spec.getSocket();
        Map<String, ValorAtributoProducto> m = attrs.getOrDefault(id, Map.of());
        String val = texto(m, "catalog_tech_cpu_socket");
        if (val == null) val = texto(m, "socket_cpu");
        return val;
    }

    private boolean cpuIncluyeDisipador(UUID id, Map<UUID, Map<String, ValorAtributoProducto>> attrs) {
        Map<String, ValorAtributoProducto> m = attrs.getOrDefault(id, Map.of());
        ValorAtributoProducto v = m.get("catalog_tech_cpu_cooler_included");
        if (v != null && v.getValorBooleano() != null) return v.getValorBooleano();
        return true;
    }

    private String obtenerTipoRamCpu(UUID id, Map<UUID, Map<String, ValorAtributoProducto>> attrs) {
        Map<String, ValorAtributoProducto> m = attrs.getOrDefault(id, Map.of());
        return texto(m, "catalog_tech_cpu_memory_type");
    }

    private boolean tieneGraficosIntegrados(UUID id, Map<UUID, EspecificacionCpu> cpusMap, Map<UUID, Map<String, ValorAtributoProducto>> attrs) {
        EspecificacionCpu spec = cpusMap.get(id);
        if (spec != null && spec.getTieneGraficosIntegrados() != null) return spec.getTieneGraficosIntegrados();
        Map<String, ValorAtributoProducto> m = attrs.getOrDefault(id, Map.of());
        ValorAtributoProducto v = m.get("catalog_tech_cpu_igpu");
        if (v == null) v = m.get("graficos_integrados_cpu");
        if (v != null && v.getValorBooleano() != null) return v.getValorBooleano();
        return false;
    }

    private BigDecimal obtenerPotenciaCpuW(UUID id, Map<UUID, EspecificacionCpu> cpusMap, Map<UUID, Map<String, ValorAtributoProducto>> attrs) {
        EspecificacionCpu spec = cpusMap.get(id);
        if (spec != null) {
            if (spec.getPotenciaMaximaW() != null) return spec.getPotenciaMaximaW();
            if (spec.getPotenciaBaseW() != null) return spec.getPotenciaBaseW();
        }
        Map<String, ValorAtributoProducto> m = attrs.getOrDefault(id, Map.of());
        BigDecimal val = numero(m, "catalog_tech_cpu_max_w");
        if (val == null) val = numero(m, "catalog_tech_cpu_tdp_w");
        if (val == null) val = numero(m, "potencia_maxima_w");
        if (val == null) val = numero(m, "potencia_base_w");
        return val;
    }

    private String obtenerSocketPlaca(UUID id, Map<UUID, RevisionPlaca> placasMap, Map<UUID, Map<String, ValorAtributoProducto>> attrs) {
        RevisionPlaca rev = placasMap.get(id);
        if (rev != null && rev.getSocket() != null) return rev.getSocket();
        Map<String, ValorAtributoProducto> m = attrs.getOrDefault(id, Map.of());
        String val = texto(m, "catalog_tech_board_socket");
        if (val == null) val = texto(m, "socket_placa");
        return val;
    }

    private String obtenerTipoRamPlaca(UUID id, Map<UUID, Map<String, ValorAtributoProducto>> attrs) {
        Map<String, ValorAtributoProducto> m = attrs.getOrDefault(id, Map.of());
        String val = texto(m, "catalog_tech_board_memory_type");
        if (val == null) val = texto(m, "tipo_ram_placa");
        return val;
    }

    private String obtenerFormFactorPlaca(UUID id, Map<UUID, Map<String, ValorAtributoProducto>> attrs) {
        Map<String, ValorAtributoProducto> m = attrs.getOrDefault(id, Map.of());
        String val = texto(m, "catalog_tech_board_form_factor");
        if (val == null) val = texto(m, "formato_placa");
        return val;
    }

    private String obtenerTipoRam(UUID id, Map<UUID, Map<String, ValorAtributoProducto>> attrs) {
        Map<String, ValorAtributoProducto> m = attrs.getOrDefault(id, Map.of());
        String val = texto(m, "catalog_tech_ram_memory_type");
        if (val == null) val = texto(m, "tipo_ram");
        return val;
    }

    private int obtenerCapacidadRamGb(UUID id, Map<UUID, Map<String, ValorAtributoProducto>> attrs) {
        Map<String, ValorAtributoProducto> m = attrs.getOrDefault(id, Map.of());
        BigDecimal val = numero(m, "catalog_tech_ram_capacity_gb");
        if (val == null) val = numero(m, "capacidad_ram_gb");
        return val != null ? val.intValue() : 0;
    }
    private String limpiarChipset(String chipset) {
        if (chipset == null) return null;
        return chipset.replaceAll("(?i)^(AMD|Intel)\\s+", "").trim();
    }

    private boolean esMejorEnsamble(EnsambleCandidato actual, EnsambleCandidato mejor, BigDecimal budget) {
        if (mejor == null) return true;
        if (actual == null) return false;

        BigDecimal precioActual = actual.precioTotal();
        BigDecimal precioMejor = mejor.precioTotal();

        boolean actualDentro = precioActual.compareTo(budget) <= 0;
        boolean mejorDentro = precioMejor.compareTo(budget) <= 0;

        // Si el ensamble actual no supera el presupuesto y el mejor sí lo supera, el actual tiene prioridad absoluta
        if (actualDentro && !mejorDentro) {
            return true;
        }
        // Si el actual supera el presupuesto y el mejor no lo supera, el mejor sigue ganando
        if (!actualDentro && mejorDentro) {
            return false;
        }
        // Si ambos están dentro del presupuesto (<= budget):
        // Criterio del usuario: "lo más aproximado al máximo sin superarlo" -> gana el de mayor precio total
        if (actualDentro && mejorDentro) {
            return precioActual.compareTo(precioMejor) > 0;
        }
        // Si ambos superan el presupuesto (caso donde el presupuesto es menor al ensamble más económico del catálogo):
        // Se escoge el más económico para minimizar el exceso
        return precioActual.compareTo(precioMejor) < 0;
    }

    private record EnsambleCandidato(
            ProductoResumenDto cpu,
            ProductoResumenDto placa,
            ProductoResumenDto ram,
            ProductoResumenDto storage,
            ProductoResumenDto gpu,
            ProductoResumenDto psu,
            ProductoResumenDto casePc,
            ProductoResumenDto cooler
    ) {
        BigDecimal precioTotal() {
            BigDecimal total = cpu.getPrecioBase()
                    .add(placa.getPrecioBase())
                    .add(ram.getPrecioBase())
                    .add(storage.getPrecioBase())
                    .add(psu.getPrecioBase())
                    .add(casePc.getPrecioBase());
            if (gpu != null) total = total.add(gpu.getPrecioBase());
            if (cooler != null) total = total.add(cooler.getPrecioBase());
            return total;
        }
    }
}
