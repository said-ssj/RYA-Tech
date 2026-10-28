package com.ecommerce.backend.controller;
import com.ecommerce.backend.service.AdminPricingService;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;import org.springframework.validation.annotation.Validated;
import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.security.oauth2.jwt.Jwt;
import java.util.*;import java.math.BigDecimal;import java.time.OffsetDateTime;
@RestController @RequiredArgsConstructor @Validated @RequestMapping("/api/v1/admin/ofertas")
public class AdminPricingController {
 private final AdminPricingService service;
 public record Price(@NotBlank @Size(max=80) String sku,@NotNull @DecimalMin("0") BigDecimal precioAnterior,@NotNull @DecimalMin("0.01") @DecimalMax("99999999.99") @Digits(integer=8,fraction=2) BigDecimal precio){}
 public record Promotion(UUID id,@NotBlank @Size(max=180) String nombre,@NotNull @DecimalMin("0.01") @DecimalMax("99.99") @Digits(integer=2,fraction=2) BigDecimal descuentoPorcentaje,@NotNull OffsetDateTime inicio,@NotNull OffsetDateTime fin,boolean activa,@NotEmpty @Size(max=100) List<@NotBlank @Size(max=80) String> skus,@Size(max=100) String version){}
 @GetMapping public List<AdminPricingService.Promotion> list(){return service.list();}
 @GetMapping("/producto") public Map<String,Object> product(@RequestParam @Size(max=80) String sku){return service.product(sku);}
 @PutMapping("/precio") public void price(@Valid @RequestBody Price data,@AuthenticationPrincipal Jwt jwt){service.price(data.sku(),data.precioAnterior(),data.precio(),UUID.fromString(jwt.getSubject()));}
 @PostMapping public Map<String,UUID> save(@Valid @RequestBody Promotion data,@AuthenticationPrincipal Jwt jwt){return Map.of("id",service.save(new AdminPricingService.Promotion(data.id(),data.nombre(),data.descuentoPorcentaje(),data.inicio(),data.fin(),data.activa(),data.skus(),data.version()),UUID.fromString(jwt.getSubject())));}
}
