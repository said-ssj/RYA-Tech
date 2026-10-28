package com.ecommerce.backend.mapper;

import com.ecommerce.backend.dto.BenchmarkCpuDto;
import com.ecommerce.backend.dto.CpuDetalleDto;
import com.ecommerce.backend.dto.CpuResumenDto;
import com.ecommerce.backend.dto.ImagenProductoDto;
import com.ecommerce.backend.entity.BenchmarkCpu;
import com.ecommerce.backend.entity.EspecificacionCpu;
import com.ecommerce.backend.entity.ImagenProducto;
import com.ecommerce.backend.entity.Producto;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CpuMapper {

    private final ProductoMapper productoMapper;

    public CpuMapper(ProductoMapper productoMapper) {
        this.productoMapper = productoMapper;
    }

    public CpuResumenDto toResumenDto(EspecificacionCpu cpu, Integer stockDisponible, String imagenUrl) {
        if (cpu == null) {
            return null;
        }

        Producto p = cpu.getProducto();
        return CpuResumenDto.builder()
                .productoId(cpu.getProductoId())
                .nombre(p != null ? p.getNombre() : cpu.getModelo())
                .slug(p != null ? p.getSlug() : null)
                .marca(p != null && p.getMarca() != null ? p.getMarca().getNombre() : null)
                .modelo(cpu.getModelo())
                .socket(cpu.getSocket())
                .familia(cpu.getFamilia())
                .generacion(cpu.getGeneracion())
                .nucleos(cpu.getNucleos())
                .hilos(cpu.getHilos())
                .frecuenciaBaseMhz(cpu.getFrecuenciaBaseMhz())
                .frecuenciaMaximaMhz(cpu.getFrecuenciaMaximaMhz())
                .potenciaBaseW(cpu.getPotenciaBaseW())
                .potenciaMaximaW(cpu.getPotenciaMaximaW())
                .tieneGraficosIntegrados(cpu.getTieneGraficosIntegrados())
                .modeloGraficos(cpu.getModeloGraficos())
                .precio(p != null ? p.getPrecioVenta() : null)
                .stockDisponible(stockDisponible != null ? stockDisponible : 0)
                .imagenUrl(imagenUrl)
                .build();
    }

    public CpuDetalleDto toDetalleDto(EspecificacionCpu cpu, List<ImagenProducto> imagenes, List<BenchmarkCpu> benchmarks, Integer stockDisponible) {
        if (cpu == null) {
            return null;
        }

        Producto p = cpu.getProducto();
        List<ImagenProductoDto> imagenesDto = (imagenes != null) ? imagenes.stream().map(productoMapper::toImagenDto).toList() : List.of();
        List<BenchmarkCpuDto> benchmarksDto = (benchmarks != null) ? benchmarks.stream().map(this::toBenchmarkDto).toList() : List.of();

        return CpuDetalleDto.builder()
                .productoId(cpu.getProductoId())
                .nombre(p != null ? p.getNombre() : cpu.getModelo())
                .slug(p != null ? p.getSlug() : null)
                .sku(p != null ? p.getSku() : null)
                .descripcion(p != null ? p.getDescripcion() : null)
                .marca(p != null && p.getMarca() != null ? p.getMarca().getNombre() : null)
                .modelo(cpu.getModelo())
                .socket(cpu.getSocket())
                .familia(cpu.getFamilia())
                .generacion(cpu.getGeneracion())
                .nucleos(cpu.getNucleos())
                .hilos(cpu.getHilos())
                .frecuenciaBaseMhz(cpu.getFrecuenciaBaseMhz())
                .frecuenciaMaximaMhz(cpu.getFrecuenciaMaximaMhz())
                .cacheL1Kb(cpu.getCacheL1Kb())
                .cacheL2Kb(cpu.getCacheL2Kb())
                .cacheL3Kb(cpu.getCacheL3Kb())
                .potenciaBaseW(cpu.getPotenciaBaseW())
                .potenciaMaximaW(cpu.getPotenciaMaximaW())
                .tieneGraficosIntegrados(cpu.getTieneGraficosIntegrados())
                .modeloGraficos(cpu.getModeloGraficos())
                .precio(p != null ? p.getPrecioVenta() : null)
                .stockDisponible(stockDisponible != null ? stockDisponible : 0)
                .imagenes(imagenesDto)
                .benchmarks(benchmarksDto)
                .build();
    }

    public BenchmarkCpuDto toBenchmarkDto(BenchmarkCpu b) {
        if (b == null) {
            return null;
        }
        return BenchmarkCpuDto.builder()
                .id(b.getId())
                .aplicacion(b.getAplicacion())
                .versionAplicacion(b.getVersionAplicacion())
                .prueba(b.getPrueba())
                .metrica(b.getMetrica())
                .unidad(b.getUnidad())
                .valor(b.getValor())
                .mayorEsMejor(b.getMayorEsMejor())
                .medidoEl(b.getMedidoEl())
                .build();
    }
}
