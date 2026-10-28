package com.ecommerce.backend.service;

import com.ecommerce.backend.dto.*;
import com.ecommerce.backend.entity.*;
import com.ecommerce.backend.exception.BadRequestException;
import com.ecommerce.backend.exception.ResourceNotFoundException;
import com.ecommerce.backend.mapper.ProductoMapper;
import com.ecommerce.backend.repository.*;
import com.ecommerce.backend.service.impl.PcBuilderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PcBuilderServiceTest {

    @Mock
    private TipoComponenteRepository tipoComponenteRepository;
    @Mock
    private ProductoRepository productoRepository;
    @Mock
    private CategoriaRepository categoriaRepository;
    @Mock
    private ImagenProductoRepository imagenProductoRepository;
    @Mock
    private ExistenciaRepository existenciaRepository;
    @Mock
    private EspecificacionCpuRepository especificacionCpuRepository;
    @Mock
    private RevisionPlacaRepository revisionPlacaRepository;
    @Mock
    private VersionBiosRepository versionBiosRepository;
    @Mock
    private CompatibilidadCpuPlacaRepository compatibilidadCpuPlacaRepository;
    @Mock
    private ValorAtributoProductoRepository valorAtributoProductoRepository;
    @Mock
    private FamiliaPerfilAsesoriaRepository familiaPerfilAsesoriaRepository;
    @Mock
    private PerfilAsesoriaRepository perfilAsesoriaRepository;
    @Mock
    private CompatibilidadCpuChipsetRepository compatibilidadCpuChipsetRepository;

    private final ProductoMapper productoMapper = new ProductoMapper();

    private PcBuilderServiceImpl pcBuilderService;

    private UUID cpuId;
    private UUID placaId;
    private UUID revisionId;
    private EspecificacionCpu cpu;
    private RevisionPlaca revision;

    @BeforeEach
    void setUp() {
        pcBuilderService = new PcBuilderServiceImpl(
                tipoComponenteRepository,
                productoRepository,
                categoriaRepository,
                imagenProductoRepository,
                existenciaRepository,
                especificacionCpuRepository,
                revisionPlacaRepository,
                versionBiosRepository,
                compatibilidadCpuPlacaRepository,
                valorAtributoProductoRepository,
                productoMapper,
                familiaPerfilAsesoriaRepository,
                perfilAsesoriaRepository,
                compatibilidadCpuChipsetRepository
        );

        cpuId = UUID.randomUUID();
        placaId = UUID.randomUUID();
        revisionId = UUID.randomUUID();

        Producto prodCpu = Producto.builder().id(cpuId).nombre("AMD Ryzen 7 7800X3D").sku("CPU-AMD-7800X3D").build();
        cpu = EspecificacionCpu.builder()
                .productoId(cpuId)
                .producto(prodCpu)
                .modelo("Ryzen 7 7800X3D")
                .socket("AM5")
                .familia("Ryzen 7")
                .generacion("Zen 4")
                .nucleos(8)
                .hilos(16)
                .potenciaBaseW(new BigDecimal("120.00"))
                .potenciaMaximaW(new BigDecimal("162.00"))
                .tieneGraficosIntegrados(true)
                .build();

        revision = RevisionPlaca.builder()
                .id(revisionId)
                .productoId(placaId)
                .modelo("B650 AORUS ELITE AX")
                .revision("Rev 1.0")
                .socket("AM5")
                .chipset("B650")
                .build();
    }

    @Test
    @DisplayName("Debe validar compatibilidad VERIFICADA cuando el fabricante certifica soporte oficial y BIOS")
    void testValidarCambioSoportado() {
        when(especificacionCpuRepository.findConProductoPorId(cpuId)).thenReturn(Optional.of(cpu));
        when(revisionPlacaRepository.findByProductoId(placaId)).thenReturn(List.of(revision));

        VersionBios biosMinima = VersionBios.builder()
                .id(UUID.randomUUID())
                .placaRevisionId(revisionId)
                .version("F1")
                .orden(1)
                .build();

        CompatibilidadCpuPlaca compat = CompatibilidadCpuPlaca.builder()
                .id(new CompatibilidadCpuPlacaId(cpuId, revisionId))
                .soporte("SOPORTADO")
                .biosMinima(biosMinima)
                .build();

        when(compatibilidadCpuPlacaRepository.findConDetalles(cpuId, revisionId)).thenReturn(Optional.of(compat));
        when(versionBiosRepository.findByPlacaRevisionIdAndVersionIgnoreCase(revisionId, "F1"))
                .thenReturn(Optional.of(biosMinima));

        ValidarCambioRequestDto req = ValidarCambioRequestDto.builder()
                .cpuId(cpuId)
                .placaId(placaId)
                .biosInstalada("F1")
                .build();

        ValidarCambioResponseDto res = pcBuilderService.validarCambio(req);

        assertNotNull(res);
        assertTrue(res.getEsCompatible());
        assertEquals("VERIFICADA", res.getEstadoCompatibilidad());
        assertEquals("F1", res.getBiosMinimaRequerida());
    }

    @Test
    @DisplayName("Una selección incompleta debe quedar POR_VERIFICAR")
    void testValidarCambioIncompleto() {
        ValidarCambioResponseDto res = pcBuilderService.validarCambio(
                ValidarCambioRequestDto.builder().cpuId(cpuId).build()
        );

        assertFalse(res.getEsCompatible());
        assertEquals("POR_VERIFICAR", res.getEstadoCompatibilidad());
        verifyNoInteractions(especificacionCpuRepository, compatibilidadCpuPlacaRepository);
    }

    @Test
    @DisplayName("Soporte oficial sin BIOS instalada debe ser CONDICIONAL")
    void testValidarCambioSinBiosInstalada() {
        when(especificacionCpuRepository.findConProductoPorId(cpuId)).thenReturn(Optional.of(cpu));
        when(revisionPlacaRepository.findByProductoId(placaId)).thenReturn(List.of(revision));

        VersionBios biosMinima = VersionBios.builder()
                .id(UUID.randomUUID()).placaRevisionId(revisionId).version("F5").orden(5).build();
        CompatibilidadCpuPlaca compat = CompatibilidadCpuPlaca.builder()
                .id(new CompatibilidadCpuPlacaId(cpuId, revisionId))
                .soporte("SOPORTADO")
                .biosMinima(biosMinima)
                .build();
        when(compatibilidadCpuPlacaRepository.findConDetalles(cpuId, revisionId)).thenReturn(Optional.of(compat));

        ValidarCambioResponseDto res = pcBuilderService.validarCambio(
                ValidarCambioRequestDto.builder().cpuId(cpuId).placaId(placaId).build()
        );

        assertTrue(res.getEsCompatible());
        assertEquals("CONDICIONAL", res.getEstadoCompatibilidad());
        assertFalse(res.getRequiereActualizacionBios());
        assertEquals("F5", res.getBiosMinimaRequerida());
    }

    @Test
    @DisplayName("Debe retornar CONDICIONAL con actualizacion de BIOS si la BIOS instalada es menor a la requerida")
    void testValidarCambioBiosMenor() {
        when(especificacionCpuRepository.findConProductoPorId(cpuId)).thenReturn(Optional.of(cpu));
        when(revisionPlacaRepository.findByProductoId(placaId)).thenReturn(List.of(revision));

        VersionBios biosMinima = VersionBios.builder()
                .id(UUID.randomUUID())
                .placaRevisionId(revisionId)
                .version("F5")
                .orden(5)
                .build();

        CompatibilidadCpuPlaca compat = CompatibilidadCpuPlaca.builder()
                .id(new CompatibilidadCpuPlacaId(cpuId, revisionId))
                .soporte("SOPORTADO")
                .biosMinima(biosMinima)
                .build();

        when(compatibilidadCpuPlacaRepository.findConDetalles(cpuId, revisionId)).thenReturn(Optional.of(compat));

        VersionBios biosInstalada = VersionBios.builder()
                .id(UUID.randomUUID())
                .placaRevisionId(revisionId)
                .version("F2")
                .orden(2)
                .build();
        when(versionBiosRepository.findByPlacaRevisionIdAndVersionIgnoreCase(revisionId, "F2"))
                .thenReturn(Optional.of(biosInstalada));

        ValidarCambioRequestDto req = ValidarCambioRequestDto.builder()
                .cpuId(cpuId)
                .placaId(placaId)
                .biosInstalada("F2")
                .build();

        ValidarCambioResponseDto res = pcBuilderService.validarCambio(req);

        assertNotNull(res);
        assertTrue(res.getEsCompatible());
        assertEquals("CONDICIONAL", res.getEstadoCompatibilidad());
        assertTrue(res.getRequiereActualizacionBios());
        assertEquals("F5", res.getBiosMinimaRequerida());
    }

    @Test
    @DisplayName("No debe declarar compatibilidad únicamente por socket si falta evidencia en tabla de compatibilidad")
    void testValidarCambioSocketCoincidePeroSinEvidencia() {
        when(especificacionCpuRepository.findConProductoPorId(cpuId)).thenReturn(Optional.of(cpu));
        when(revisionPlacaRepository.findByProductoId(placaId)).thenReturn(List.of(revision));
        when(compatibilidadCpuPlacaRepository.findConDetalles(cpuId, revisionId)).thenReturn(Optional.empty());

        ValidarCambioRequestDto req = ValidarCambioRequestDto.builder()
                .cpuId(cpuId)
                .placaId(placaId)
                .build();

        ValidarCambioResponseDto res = pcBuilderService.validarCambio(req);

        assertNotNull(res);
        assertFalse(res.getEsCompatible());
        assertEquals("POR_VERIFICAR", res.getEstadoCompatibilidad());
        assertTrue(res.getMensajes().get(0).contains("No se declara compatibilidad únicamente por socket"));
    }

    @Test
    @DisplayName("Debe calcular total de configuración en BigDecimal y estimar potencia y fuente recomendada")
    void testCalcularConfiguracion() {
        Producto prod = Producto.builder()
                .id(cpuId)
                .nombre("AMD Ryzen 7 7800X3D")
                .sku("CPU-AMD-7800X3D")
                .precioBase(new BigDecimal("1650.00"))
                .activo(true)
                .vendible(true)
                .isDeleted(false)
                .build();

        when(productoRepository.findAllById(List.of(cpuId))).thenReturn(List.of(prod));
        List<Object[]> stockRows = Collections.singletonList(new Object[]{cpuId, 5L});
        when(existenciaRepository.findStockDisponiblePorProductoIds(List.of(cpuId)))
                .thenReturn(stockRows);
        when(especificacionCpuRepository.findById(cpuId)).thenReturn(Optional.of(cpu));

        CalcularConfiguracionRequestDto req = CalcularConfiguracionRequestDto.builder()
                .items(List.of(ItemConfiguracionDto.builder().productoId(cpuId).cantidad(1).build()))
                .build();

        CalcularConfiguracionResponseDto res = pcBuilderService.calcularConfiguracion(req);

        assertNotNull(res);
        assertEquals(new BigDecimal("1650.00"), res.getPrecioTotal());
        assertEquals(new BigDecimal("162.00"), res.getConsumoEstimadoW());
        assertNotNull(res.getFuenteRecomendadaW());
        assertTrue(res.getFuenteRecomendadaW() >= 450);
        assertTrue(res.getTodosDisponibles());
    }

    @Test
    @DisplayName("Debe rechazar RAM cuyo tipo no coincide con la placa madre")
    void testRamIncompatibleConPlaca() {
        prepararPlataformaVerificada();
        UUID ramId = UUID.randomUUID();
        Producto placa = Producto.builder().id(placaId).build();
        Producto ram = Producto.builder().id(ramId).build();
        when(valorAtributoProductoRepository.findConAtributoPorProductoIds(anyList())).thenReturn(List.of(
                atributoTexto(placa, "tipo_ram_placa", "DDR5"),
                atributoTexto(ram, "tipo_ram", "DDR4"),
                atributoNumero(placa, "capacidad_ram_max_placa_gb", "128"),
                atributoNumero(ram, "capacidad_ram_gb", "32"),
                atributoNumero(placa, "ranuras_ram_placa", "4"),
                atributoNumero(ram, "modulos_ram", "2"),
                atributoNumero(placa, "velocidad_ram_max_placa_mt_s", "6400"),
                atributoNumero(ram, "velocidad_ram_mt_s", "6000")
        ));

        ValidarCambioResponseDto resultado = pcBuilderService.validarCambio(ValidarCambioRequestDto.builder()
                .cpuId(cpuId).placaId(placaId).biosInstalada("F1").ramId(ramId).build());

        assertFalse(resultado.getEsCompatible());
        assertEquals("INCOMPATIBLE", resultado.getEstadoCompatibilidad());
        assertTrue(resultado.getMensajes().stream().anyMatch(m -> m.contains("DDR4") && m.contains("DDR5")));
    }

    @Test
    @DisplayName("Debe impedir retirar un componente bloqueado")
    void testRespetaComponenteBloqueado() {
        prepararPlataformaVerificada();
        UUID bloqueado = UUID.randomUUID();

        ValidarCambioResponseDto resultado = pcBuilderService.validarCambio(ValidarCambioRequestDto.builder()
                .cpuId(cpuId).placaId(placaId).biosInstalada("F1")
                .componentesBloqueados(List.of(bloqueado))
                .build());

        assertFalse(resultado.getEsCompatible());
        assertEquals("INCOMPATIBLE", resultado.getEstadoCompatibilidad());
        assertTrue(resultado.getMensajes().stream().anyMatch(m -> m.contains("bloqueados")));
    }

    private void prepararPlataformaVerificada() {
        when(especificacionCpuRepository.findConProductoPorId(cpuId)).thenReturn(Optional.of(cpu));
        when(revisionPlacaRepository.findByProductoId(placaId)).thenReturn(List.of(revision));
        VersionBios bios = VersionBios.builder()
                .id(UUID.randomUUID()).placaRevisionId(revisionId).version("F1").orden(1).build();
        CompatibilidadCpuPlaca compatibilidad = CompatibilidadCpuPlaca.builder()
                .id(new CompatibilidadCpuPlacaId(cpuId, revisionId))
                .soporte("SOPORTADO").biosMinima(bios).build();
        when(compatibilidadCpuPlacaRepository.findConDetalles(cpuId, revisionId)).thenReturn(Optional.of(compatibilidad));
        when(versionBiosRepository.findByPlacaRevisionIdAndVersionIgnoreCase(revisionId, "F1"))
                .thenReturn(Optional.of(bios));
    }

    @Test
    @DisplayName("recomendarConfiguracion: lanza excepción si la solicitud o perfil es nulo")
    void recomendarConfiguracion_parametrosInvalidos() {
        assertThrows(BadRequestException.class, () -> pcBuilderService.recomendarConfiguracion(null));

        RecomendarPcRequestDto sinPerfil = RecomendarPcRequestDto.builder()
                .budget(new BigDecimal("3500.00"))
                .build();
        assertThrows(BadRequestException.class, () -> pcBuilderService.recomendarConfiguracion(sinPerfil));

        RecomendarPcRequestDto presupuestoBajo = RecomendarPcRequestDto.builder()
                .profileId(UUID.randomUUID())
                .budget(new BigDecimal("200.00"))
                .build();
        assertThrows(BadRequestException.class, () -> pcBuilderService.recomendarConfiguracion(presupuestoBajo));
    }

    @Test
    @DisplayName("recomendarConfiguracion: lanza ResourceNotFoundException si el perfil no existe")
    void recomendarConfiguracion_perfilNoExiste() {
        UUID perfilId = UUID.randomUUID();
        when(perfilAsesoriaRepository.findById(perfilId)).thenReturn(Optional.empty());

        RecomendarPcRequestDto req = RecomendarPcRequestDto.builder()
                .profileId(perfilId)
                .budget(new BigDecimal("4000.00"))
                .build();

        assertThrows(ResourceNotFoundException.class, () -> pcBuilderService.recomendarConfiguracion(req));
    }

    @Test
    @DisplayName("recomendarConfiguracion: arma una PC compatible y balanceada según el perfil y presupuesto")
    void recomendarConfiguracion_ensambleExitoso() {
        UUID perfilId = UUID.randomUUID();
        PerfilAsesoria perfil = PerfilAsesoria.builder()
                .id(perfilId)
                .nombre("Gaming 1080p")
                .codigo("GAMING-1080P")
                .prioridadCpu(7)
                .prioridadGpu(8)
                .ramMinimaGb(16)
                .requiereGpuDedicada(true)
                .activo(true)
                .isDeleted(false)
                .build();
        when(perfilAsesoriaRepository.findById(perfilId)).thenReturn(Optional.of(perfil));

        List<TipoComponente> tipos = List.of(
                TipoComponente.builder().id(UUID.randomUUID()).codigo("CPU").nombre("Procesador").obligatorio(true).build(),
                TipoComponente.builder().id(UUID.randomUUID()).codigo("PLACA").nombre("Placa Madre").obligatorio(true).build(),
                TipoComponente.builder().id(UUID.randomUUID()).codigo("RAM").nombre("Memoria RAM").obligatorio(true).build(),
                TipoComponente.builder().id(UUID.randomUUID()).codigo("STORAGE").nombre("Almacenamiento").obligatorio(true).build(),
                TipoComponente.builder().id(UUID.randomUUID()).codigo("GPU").nombre("Tarjeta Gráfica").obligatorio(false).build(),
                TipoComponente.builder().id(UUID.randomUUID()).codigo("PSU").nombre("Fuente de Poder").obligatorio(true).build(),
                TipoComponente.builder().id(UUID.randomUUID()).codigo("CASE").nombre("Gabinete").obligatorio(true).build()
        );
        when(tipoComponenteRepository.findByIsDeletedFalseOrderByCodigoAsc()).thenReturn(tipos);
        when(familiaPerfilAsesoriaRepository.findByActivoTrueAndIsDeletedFalseOrderByPosicionAsc()).thenReturn(List.of());

        // Productos
        Producto prodCpu = Producto.builder().id(cpuId).nombre("AMD Ryzen 7 7800X3D").sku("CPU-001").precioBase(new BigDecimal("1200.00")).activo(true).vendible(true).build();
        Producto prodPlaca = Producto.builder().id(placaId).nombre("MSI B650 Gaming").sku("MB-001").precioBase(new BigDecimal("700.00")).activo(true).vendible(true).build();
        UUID ramId = UUID.randomUUID();
        Producto prodRam = Producto.builder().id(ramId).nombre("Corsair 32GB DDR5").sku("RAM-001").precioBase(new BigDecimal("450.00")).activo(true).vendible(true).build();
        UUID storageId = UUID.randomUUID();
        Producto prodStorage = Producto.builder().id(storageId).nombre("Kingston 1TB NVMe").sku("SSD-001").precioBase(new BigDecimal("300.00")).activo(true).vendible(true).build();
        UUID gpuId = UUID.randomUUID();
        Producto prodGpu = Producto.builder().id(gpuId).nombre("RTX 4060 8GB").sku("GPU-001").precioBase(new BigDecimal("1400.00")).activo(true).vendible(true).build();
        UUID psuId = UUID.randomUUID();
        Producto prodPsu = Producto.builder().id(psuId).nombre("Corsair 650W Bronze").sku("PSU-001").precioBase(new BigDecimal("350.00")).activo(true).vendible(true).build();
        UUID caseId = UUID.randomUUID();
        Producto prodCase = Producto.builder().id(caseId).nombre("NZXT H5 Flow").sku("CASE-001").precioBase(new BigDecimal("320.00")).activo(true).vendible(true).build();

        for (TipoComponente tc : tipos) {
            when(tipoComponenteRepository.findByCodigoIgnoreCaseAndIsDeletedFalse(tc.getCodigo())).thenReturn(Optional.of(tc));
            List<Producto> prods = switch (tc.getCodigo()) {
                case "CPU" -> List.of(prodCpu);
                case "PLACA" -> List.of(prodPlaca);
                case "RAM" -> List.of(prodRam);
                case "STORAGE" -> List.of(prodStorage);
                case "GPU" -> List.of(prodGpu);
                case "PSU" -> List.of(prodPsu);
                case "CASE" -> List.of(prodCase);
                default -> List.of();
            };
            when(productoRepository.findActivosPorCategoriaAcronimo(tc.getCodigo())).thenReturn(prods);
        }

        // Mocks de stock
        List<UUID> todosProds = List.of(cpuId, placaId, ramId, storageId, gpuId, psuId, caseId);
        List<Object[]> stockRows = todosProds.stream()
                .map(id -> new Object[]{id, 5L})
                .toList();
        when(existenciaRepository.findStockDisponiblePorProductoIds(anyList())).thenReturn(stockRows);

        // Mocks de specs
        when(especificacionCpuRepository.findConProductoPorId(cpuId)).thenReturn(Optional.of(cpu));
        when(revisionPlacaRepository.findByProductoId(placaId)).thenReturn(List.of(revision));
        when(productoRepository.findAllById(anyList())).thenReturn(List.of(prodCpu, prodPlaca, prodRam, prodStorage, prodGpu, prodPsu, prodCase));

        // Atributos
        List<ValorAtributoProducto> attrs = List.of(
                atributoTexto(prodCpu, "catalog_tech_cpu_socket", "AM5"),
                atributoTexto(prodPlaca, "catalog_tech_board_socket", "AM5"),
                atributoTexto(prodPlaca, "catalog_tech_board_memory_type", "DDR5"),
                atributoTexto(prodPlaca, "catalog_tech_board_form_factor", "ATX"),
                atributoTexto(prodRam, "catalog_tech_ram_memory_type", "DDR5"),
                atributoNumero(prodRam, "catalog_tech_ram_capacity_gb", "32"),
                atributoNumero(prodGpu, "catalog_tech_gpu_board_w", "160"),
                atributoNumero(prodPsu, "catalog_tech_psu_continuous_w", "650"),
                atributoTexto(prodCase, "catalog_tech_case_form_factor", "ATX, Micro-ATX")
        );
        when(valorAtributoProductoRepository.findConAtributoPorProductoIds(anyList())).thenReturn(attrs);
        when(compatibilidadCpuPlacaRepository.findConDetalles(cpuId, revisionId)).thenReturn(Optional.of(
                CompatibilidadCpuPlaca.builder().soporte("SOPORTADO").build()
        ));

        RecomendarPcRequestDto request = RecomendarPcRequestDto.builder()
                .profileId(perfilId)
                .budget(new BigDecimal("4800.00"))
                .build();

        RecomendarPcResponseDto respuesta = pcBuilderService.recomendarConfiguracion(request);

        assertNotNull(respuesta);
        assertTrue(respuesta.getCompatible());
        assertNotNull(respuesta.getTotal());
        assertTrue(respuesta.getTotal().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(respuesta.getTotal().compareTo(new BigDecimal("4800.00")) <= 0, "No debe superar el presupuesto máximo");
        assertTrue(respuesta.getComponents().size() >= 6);
        assertEquals("PEN", respuesta.getCurrency());
        assertTrue(respuesta.getPowerDrawWatts() > 0);
        assertTrue(respuesta.getRecommendedPsuWatts() > 0);
    }

    @Test
    @DisplayName("Debe validar compatibilidad nativa usando la matriz de socket, generación y chipset")
    void testValidarCpuPlacaConMatrizChipsetNativa() {
        when(especificacionCpuRepository.findConProductoPorId(cpuId)).thenReturn(Optional.of(cpu));
        when(revisionPlacaRepository.findByProductoId(placaId)).thenReturn(List.of()); // Sin revisión registrada
        when(valorAtributoProductoRepository.findConAtributoPorProductoIds(anyList())).thenReturn(List.of(
                atributoTexto(Producto.builder().id(placaId).build(), "catalog_tech_board_socket", "AM5"),
                atributoTexto(Producto.builder().id(placaId).build(), "catalog_tech_board_chipset", "AMD B650")
        ));
        when(compatibilidadCpuChipsetRepository.findFirstBySocketIgnoreCaseAndGeneracionIgnoreCaseAndChipsetIgnoreCase("AM5", "Zen 4", "B650"))
                .thenReturn(Optional.of(CompatibilidadCpuChipset.builder()
                        .socket("AM5")
                        .generacion("Zen 4")
                        .chipset("B650")
                        .requiereActualizacion(false)
                        .build()));

        ValidarCambioRequestDto req = ValidarCambioRequestDto.builder()
                .cpuId(cpuId)
                .placaId(placaId)
                .build();

        ValidarCambioResponseDto res = pcBuilderService.validarCambio(req);

        assertNotNull(res);
        assertTrue(res.getEsCompatible());
        assertEquals("VERIFICADA", res.getEstadoCompatibilidad());
        assertFalse(res.getRequiereActualizacionBios());
    }

    @Test void opcionesSoloAceptanRelacionesVerificadasYReconocenCategoriasPlurales(){
        var service=spy(pcBuilderService);
        UUID ramId=UUID.randomUUID();
        var board=Producto.builder().id(placaId).categoria(Categoria.builder().slug("placas-madre").build()).build();
        var ram=Producto.builder().id(ramId).categoria(Categoria.builder().slug("memorias-ram").build()).build();
        doReturn(ComponenteTipoDto.builder().productos(List.of(ProductoResumenDto.builder().id(ramId).build())).build()).when(service).listarComponentesPorTipo("RAM");
        when(productoRepository.findConDetallesPorId(placaId)).thenReturn(Optional.of(board));
        when(productoRepository.findConDetallesPorId(ramId)).thenReturn(Optional.of(ram));
        var attrs=List.of(atributoTexto(board,"tipo_ram_placa","DDR5"),atributoTexto(ram,"tipo_ram","DDR5"),
            atributoNumero(board,"capacidad_ram_max_placa_gb","128"),atributoNumero(ram,"capacidad_ram_gb","32"),
            atributoNumero(board,"ranuras_ram_placa","4"),atributoNumero(ram,"modulos_ram","2"),
            atributoNumero(board,"velocidad_ram_max_placa_mt_s","6400"),atributoNumero(ram,"velocidad_ram_mt_s","6000"));
        when(valorAtributoProductoRepository.findConAtributoPorProductoIds(anyList())).thenReturn(attrs);
        assertEquals(1,service.obtenerComponentesCompatibles("RAM",List.of(placaId)).size());
        attrs.get(1).setValorTexto("DDR4");assertTrue(service.obtenerComponentesCompatibles("RAM",List.of(placaId)).isEmpty());
        when(valorAtributoProductoRepository.findConAtributoPorProductoIds(anyList())).thenReturn(List.of());
        assertTrue(service.obtenerComponentesCompatibles("RAM",List.of(placaId)).isEmpty());
    }

    private ValorAtributoProducto atributoTexto(Producto producto, String codigo, String valor) {
        return ValorAtributoProducto.builder()
                .producto(producto)
                .atributo(AtributoTecnico.builder().codigo(codigo).build())
                .valorTexto(valor)
                .build();
    }

    private ValorAtributoProducto atributoNumero(Producto producto, String codigo, String valor) {
        return ValorAtributoProducto.builder()
                .producto(producto)
                .atributo(AtributoTecnico.builder().codigo(codigo).build())
                .valorNumero(new BigDecimal(valor))
                .build();
    }
}
