package com.ecommerce.backend.service.impl;

import com.ecommerce.backend.dto.*;
import com.ecommerce.backend.entity.BenchmarkCpu;
import com.ecommerce.backend.entity.EspecificacionCpu;
import com.ecommerce.backend.entity.ImagenProducto;
import com.ecommerce.backend.exception.BadRequestException;
import com.ecommerce.backend.exception.ResourceNotFoundException;
import com.ecommerce.backend.mapper.CpuMapper;
import com.ecommerce.backend.repository.BenchmarkCpuRepository;
import com.ecommerce.backend.repository.EspecificacionCpuRepository;
import com.ecommerce.backend.repository.ExistenciaRepository;
import com.ecommerce.backend.repository.ImagenProductoRepository;
import com.ecommerce.backend.service.CpuService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CpuServiceImpl implements CpuService {

    private final EspecificacionCpuRepository especificacionCpuRepository;
    private final BenchmarkCpuRepository benchmarkCpuRepository;
    private final ImagenProductoRepository imagenProductoRepository;
    private final ExistenciaRepository existenciaRepository;
    private final CpuMapper cpuMapper;

    @Override
    public List<CpuResumenDto> listarCpus(String marca, String socket, String familia, BigDecimal precioMin, BigDecimal precioMax) {
        List<EspecificacionCpu> cpus = especificacionCpuRepository.findAllActivos();

        return cpus.stream()
                .filter(c -> marca == null || (c.getProducto().getMarca() != null &&
                        c.getProducto().getMarca().getNombre().equalsIgnoreCase(marca.trim())))
                .filter(c -> socket == null || c.getSocket().equalsIgnoreCase(socket.trim()))
                .filter(c -> familia == null || c.getFamilia().equalsIgnoreCase(familia.trim()))
                .filter(c -> precioMin == null || (c.getProducto().getPrecioVenta() != null &&
                        c.getProducto().getPrecioVenta().compareTo(precioMin) >= 0))
                .filter(c -> precioMax == null || (c.getProducto().getPrecioVenta() != null &&
                        c.getProducto().getPrecioVenta().compareTo(precioMax) <= 0))
                .map(this::construirResumenDto)
                .toList();
    }

    @Override
    public CpuDetalleDto obtenerCpuPorId(UUID id) {
        EspecificacionCpu cpu = especificacionCpuRepository.findConProductoPorId(id)
                .orElseThrow(() -> new ResourceNotFoundException("No se encontró especificación técnica para la CPU con ID: " + id));

        return construirDetalleDto(cpu);
    }

    @Override
    public List<CpuResumenDto> obtenerAlternativas(UUID id) {
        EspecificacionCpu cpuReferencia = especificacionCpuRepository.findConProductoPorId(id)
                .orElseThrow(() -> new ResourceNotFoundException("CPU de referencia no encontrada: " + id));

        // Buscar alternativas por mismo socket primero
        List<EspecificacionCpu> alternativas = especificacionCpuRepository.findAlternativasPorSocket(
                cpuReferencia.getSocket(), cpuReferencia.getProductoId());

        if (alternativas.isEmpty()) {
            // Si no hay con mismo socket, buscar por misma familia
            alternativas = especificacionCpuRepository.findAlternativasPorFamilia(
                    cpuReferencia.getFamilia(), cpuReferencia.getProductoId());
        }

        return alternativas.stream()
                .limit(5)
                .map(this::construirResumenDto)
                .toList();
    }

    @Override
    public CpuComparacionResponseDto compararCpus(CpuComparacionRequestDto request) {
        if (request == null || request.getCpuIds() == null || request.getCpuIds().isEmpty()) {
            throw new BadRequestException("Debe proporcionar al menos un ID de procesador");
        }

        if (request.getCpuIds().size() > 3) {
            throw new BadRequestException("Se permite comparar como máximo 3 procesadores simultáneamente");
        }

        List<UUID> uniqueIds = request.getCpuIds().stream().distinct().toList();
        List<CpuDetalleDto> procesadores = new ArrayList<>();

        for (UUID cpuId : uniqueIds) {
            EspecificacionCpu cpu = especificacionCpuRepository.findConProductoPorId(cpuId)
                    .orElseThrow(() -> new ResourceNotFoundException("Procesador no encontrado con ID: " + cpuId));
            procesadores.add(construirDetalleDto(cpu));
        }

        // Calcular diferencias de precio respecto a la primera CPU (referencia)
        Map<UUID, BigDecimal> diferenciasPrecio = new HashMap<>();
        BigDecimal precioReferencia = procesadores.get(0).getPrecio();

        for (CpuDetalleDto cpuDto : procesadores) {
            if (precioReferencia != null && cpuDto.getPrecio() != null) {
                diferenciasPrecio.put(cpuDto.getProductoId(), cpuDto.getPrecio().subtract(precioReferencia));
            } else {
                diferenciasPrecio.put(cpuDto.getProductoId(), BigDecimal.ZERO);
            }
        }

        // Cargar benchmarks comparativos en común
        List<BenchmarkCpu> allBenchmarks = benchmarkCpuRepository.findByCpuIdInOrderByAplicacionAscPruebaAsc(uniqueIds);
        Map<String, BenchmarkComparadoDto> benchmarkMap = new LinkedHashMap<>();

        for (BenchmarkCpu b : allBenchmarks) {
            String key = b.getAplicacion() + " - " + b.getPrueba();
            BenchmarkComparadoDto comp = benchmarkMap.computeIfAbsent(key, k -> BenchmarkComparadoDto.builder()
                    .aplicacion(b.getAplicacion())
                    .prueba(b.getPrueba())
                    .metrica(b.getMetrica())
                    .unidad(b.getUnidad())
                    .mayorEsMejor(b.getMayorEsMejor())
                    .puntuaciones(new HashMap<>())
                    .build());

            comp.getPuntuaciones().put(b.getCpuId(), b.getValor());
        }

        return CpuComparacionResponseDto.builder()
                .procesadores(procesadores)
                .diferenciaPrecioReferencia(diferenciasPrecio)
                .benchmarks(new ArrayList<>(benchmarkMap.values()))
                .build();
    }

    private CpuResumenDto construirResumenDto(EspecificacionCpu cpu) {
        Integer stock = existenciaRepository.findStockDisponiblePorProductoId(cpu.getProductoId());
        Optional<ImagenProducto> imgOpt = imagenProductoRepository.findFirstByProductoIdAndPrincipalTrueAndIsDeletedFalse(cpu.getProductoId());
        String imgUrl = imgOpt.map(ImagenProducto::getUrl).orElse(null);

        return cpuMapper.toResumenDto(cpu, stock, imgUrl);
    }

    private CpuDetalleDto construirDetalleDto(EspecificacionCpu cpu) {
        List<ImagenProducto> imagenes = imagenProductoRepository.findByProductoIdAndIsDeletedFalseOrderByPosicionAsc(cpu.getProductoId());
        List<BenchmarkCpu> benchmarks = benchmarkCpuRepository.findByCpuIdOrderByAplicacionAscPruebaAsc(cpu.getProductoId());
        Integer stock = existenciaRepository.findStockDisponiblePorProductoId(cpu.getProductoId());

        return cpuMapper.toDetalleDto(cpu, imagenes, benchmarks, stock);
    }
}
