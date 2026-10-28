package com.ecommerce.backend.controller;
import com.ecommerce.backend.service.CustomerFeedbackService;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.*;
@RestController @RequiredArgsConstructor
public class CustomerFeedbackController {
 private final CustomerFeedbackService service;
 public record Review(@Min(1) @Max(5) int calificacion,@NotBlank @Size(min=10,max=2000) String comentario){}
 public record Question(@NotBlank @Size(min=5,max=220) String asunto,@NotBlank @Size(min=10,max=5000) String mensaje){}
 public record Answer(@NotBlank @Size(min=10,max=5000) String mensaje){}
 @PostMapping("/api/v1/productos/{id}/resenas") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
 public Map<String,Object> review(@PathVariable UUID id,@Valid @RequestBody Review body,@AuthenticationPrincipal Jwt jwt){return Map.of("id",service.review(UUID.fromString(jwt.getSubject()),id,body.calificacion(),body.comentario()),"estado","PENDIENTE");}
 @PostMapping("/api/v1/ayuda/consultas") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
 public Map<String,Object> question(@Valid @RequestBody Question body,@AuthenticationPrincipal Jwt jwt){return Map.of("id",service.question(UUID.fromString(jwt.getSubject()),body.asunto(),body.mensaje()),"estado","ABIERTO");}
 @GetMapping("/api/v1/ayuda/consultas") public List<Map<String,Object>> own(@AuthenticationPrincipal Jwt jwt){return service.questions(UUID.fromString(jwt.getSubject()),false);}
 @GetMapping("/api/v1/admin/ayuda/consultas") public List<Map<String,Object>> all(@AuthenticationPrincipal Jwt jwt){return service.questions(UUID.fromString(jwt.getSubject()),true);}
 @PostMapping("/api/v1/admin/ayuda/consultas/{id}/respuesta") public void answer(@PathVariable UUID id,@Valid @RequestBody Answer body,@AuthenticationPrincipal Jwt jwt){service.answer(UUID.fromString(jwt.getSubject()),id,body.mensaje());}
}
