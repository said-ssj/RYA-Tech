package com.ecommerce.backend.service.impl;

import com.ecommerce.backend.dto.*;
import com.ecommerce.backend.entity.*;
import com.ecommerce.backend.exception.BadRequestException;
import com.ecommerce.backend.exception.ResourceNotFoundException;
import com.ecommerce.backend.repository.*;
import com.ecommerce.backend.service.CarritoService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CarritoServiceImpl implements CarritoService {

    private static final String ABIERTO = "ABIERTO";

    private final CarritoRepository carritoRepository;
    private final ItemCarritoRepository itemRepository;
    private final ProductoRepository productoRepository;
    private final ExistenciaRepository existenciaRepository;
    private final ImagenProductoRepository imagenProductoRepository;

    @Override
    public CarritoDto obtener(String sessionKey, UUID usuarioId) {
        return buscarCarrito(sessionKey, usuarioId).map(this::mapear).orElseGet(this::vacio);
    }

    @Override
    @Transactional
    public CarritoDto agregar(String sessionKey, UUID usuarioId, AgregarItemCarritoRequestDto request) {
        validarCantidad(request.getCantidad());
        Producto producto = productoRepository.findConDetallesPorId(request.getProductoId())
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado: " + request.getProductoId()));
        validarProductoVendible(producto);

        Carrito carrito = buscarCarrito(sessionKey, usuarioId).orElseGet(() -> carritoRepository.save(Carrito.builder()
                .usuarioId(usuarioId).sessionKeyHash(usuarioId == null ? hashSession(sessionKey) : null)
                .estado(ABIERTO).moneda("PEN").build()));
        ItemCarrito item = itemRepository.findByCarritoIdAndProductoIdAndIsDeletedFalse(carrito.getId(), producto.getId())
                .orElse(null);
        int cantidadFinal = request.getCantidad() + (item == null ? 0 : item.getCantidad());
        validarStock(producto.getId(), cantidadFinal);

        if (item == null) {
            item = ItemCarrito.builder().carrito(carrito).producto(producto).cantidad(cantidadFinal)
                    .precioReferencia(producto.getPrecioVenta()).build();
        } else {
            item.setCantidad(cantidadFinal);
            item.setPrecioReferencia(producto.getPrecioVenta());
        }
        itemRepository.save(item);
        return mapear(carrito);
    }

    @Override
    @Transactional
    public CarritoDto actualizar(String sessionKey, UUID usuarioId, UUID itemId, ActualizarItemCarritoRequestDto request) {
        validarCantidad(request.getCantidad());
        Carrito carrito = buscarCarrito(sessionKey, usuarioId)
                .orElseThrow(() -> new ResourceNotFoundException("Carrito no encontrado"));
        ItemCarrito item = buscarItem(carrito, itemId);
        validarProductoVendible(item.getProducto());
        validarStock(item.getProducto().getId(), request.getCantidad());
        item.setCantidad(request.getCantidad());
        item.setPrecioReferencia(item.getProducto().getPrecioVenta());
        itemRepository.save(item);
        return mapear(carrito);
    }

    @Override
    @Transactional
    public CarritoDto eliminar(String sessionKey, UUID usuarioId, UUID itemId) {
        Carrito carrito = buscarCarrito(sessionKey, usuarioId)
                .orElseThrow(() -> new ResourceNotFoundException("Carrito no encontrado"));
        ItemCarrito item = buscarItem(carrito, itemId);
        item.setIsDeleted(true);
        itemRepository.save(item);
        return mapear(carrito);
    }

    @Override
    @Transactional
    public CarritoDto vincular(String sessionKey, UUID usuarioId) {
        if (usuarioId == null) throw new BadRequestException("Debes iniciar sesión para vincular el carrito");
        Optional<Carrito> anonimo = buscarCarritoAnonimo(sessionKey);
        Optional<Carrito> delUsuario = carritoRepository
                .findFirstByUsuarioIdAndEstadoAndIsDeletedFalseOrderByCreatedAtDesc(usuarioId, ABIERTO);
        if (anonimo.isEmpty()) return delUsuario.map(this::mapear).orElseGet(this::vacio);
        if (delUsuario.isEmpty()) {
            Carrito adoptado = anonimo.get();
            adoptado.setUsuarioId(usuarioId);
            adoptado.setSessionKeyHash(null);
            return mapear(carritoRepository.save(adoptado));
        }

        Carrito origen = anonimo.get();
        Carrito destino = delUsuario.get();
        if (!origen.getId().equals(destino.getId())) {
            for (ItemCarrito itemOrigen : itemRepository.findByCarritoIdAndIsDeletedFalseOrderByCreatedAtAsc(origen.getId())) {
                Producto producto = itemOrigen.getProducto();
                ItemCarrito destinoItem = itemRepository
                        .findByCarritoIdAndProductoIdAndIsDeletedFalse(destino.getId(), producto.getId()).orElse(null);
                if (destinoItem == null) {
                    destinoItem = ItemCarrito.builder().carrito(destino).producto(producto)
                            .cantidad(itemOrigen.getCantidad()).precioReferencia(producto.getPrecioVenta()).build();
                } else {
                    destinoItem.setCantidad(destinoItem.getCantidad() + itemOrigen.getCantidad());
                    destinoItem.setPrecioReferencia(producto.getPrecioVenta());
                }
                itemRepository.save(destinoItem);
            }
            origen.setEstado("ABANDONADO");
            carritoRepository.save(origen);
        }
        return mapear(destino);
    }

    private Optional<Carrito> buscarCarrito(String sessionKey, UUID usuarioId) {
        if (usuarioId != null) {
            return carritoRepository.findFirstByUsuarioIdAndEstadoAndIsDeletedFalseOrderByCreatedAtDesc(usuarioId, ABIERTO);
        }
        return buscarCarritoAnonimo(sessionKey);
    }

    private Optional<Carrito> buscarCarritoAnonimo(String sessionKey) {
        return carritoRepository.findFirstBySessionKeyHashAndEstadoAndIsDeletedFalseOrderByCreatedAtDesc(
                hashSession(sessionKey), ABIERTO);
    }

    private ItemCarrito buscarItem(Carrito carrito, UUID itemId) {
        return itemRepository.findByIdAndCarritoIdAndIsDeletedFalse(itemId, carrito.getId())
                .orElseThrow(() -> new ResourceNotFoundException("El producto no pertenece al carrito actual"));
    }

    private void validarProductoVendible(Producto producto) {
        if (!Boolean.TRUE.equals(producto.getActivo()) || !Boolean.TRUE.equals(producto.getVendible())
                || Boolean.TRUE.equals(producto.getIsDeleted())) {
            throw new BadRequestException("El producto ya no está disponible para la venta");
        }
        if (producto.getPrecioVenta() == null) {
            throw new BadRequestException("El producto no tiene un precio vigente");
        }
    }

    private void validarStock(UUID productoId, int cantidad) {
        int disponible = Optional.ofNullable(existenciaRepository.findStockDisponiblePorProductoId(productoId)).orElse(0);
        if (disponible < cantidad) {
            throw new BadRequestException("Stock insuficiente. Disponible: " + disponible + ", solicitado: " + cantidad);
        }
    }

    private void validarCantidad(Integer cantidad) {
        if (cantidad == null || cantidad < 1) throw new BadRequestException("La cantidad debe ser al menos 1");
    }

    private CarritoDto mapear(Carrito carrito) {
        List<ItemCarritoDto> items = itemRepository
                .findByCarritoIdAndIsDeletedFalseOrderByCreatedAtAsc(carrito.getId()).stream()
                .map(item -> {
                    Producto producto = item.getProducto();
                    int stock = Optional.ofNullable(existenciaRepository
                            .findStockDisponiblePorProductoId(producto.getId())).orElse(0);
                    String imagen = imagenProductoRepository
                            .findFirstByProductoIdAndPrincipalTrueAndIsDeletedFalse(producto.getId())
                            .map(ImagenProducto::getUrl).orElse(null);
                    BigDecimal precioActual = producto.getPrecioVenta();
                    boolean actualizado = item.getPrecioReferencia() != null && precioActual != null
                            && item.getPrecioReferencia().compareTo(precioActual) != 0;
                    return ItemCarritoDto.builder().id(item.getId()).productoId(producto.getId())
                            .sku(producto.getSku()).nombre(producto.getNombre()).slug(producto.getSlug())
                            .imagen(imagen).cantidad(item.getCantidad()).stockDisponible(stock)
                            .precioUnitario(precioActual).subtotal(precioActual.multiply(BigDecimal.valueOf(item.getCantidad())))
                            .precioActualizado(actualizado).build();
                }).toList();
        BigDecimal subtotal = items.stream().map(ItemCarritoDto::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int cantidad = items.stream().mapToInt(ItemCarritoDto::getCantidad).sum();
        return CarritoDto.builder().id(carrito.getId()).moneda(carrito.getMoneda())
                .items(items).cantidadTotal(cantidad).subtotal(subtotal).build();
    }

    private CarritoDto vacio() {
        return CarritoDto.builder().moneda("PEN").items(List.of()).cantidadTotal(0)
                .subtotal(BigDecimal.ZERO).build();
    }

    private String hashSession(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank() || sessionKey.length() < 16 || sessionKey.length() > 200) {
            throw new BadRequestException("La sesión anónima del carrito no es válida");
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(sessionKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no está disponible", ex);
        }
    }
}
