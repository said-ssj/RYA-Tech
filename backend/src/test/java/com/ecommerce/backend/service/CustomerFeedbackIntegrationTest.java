package com.ecommerce.backend.service;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc @Transactional
class CustomerFeedbackIntegrationTest {
 @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired CustomerFeedbackService service;
 UUID user=UUID.randomUUID(),other=UUID.randomUUID(),product=UUID.randomUUID();
 @BeforeEach void setup(){
  for(UUID id:List.of(user,other))jdbc.update("INSERT INTO rya.usuarios(id,nombre,email) VALUES (?,'Prueba','test@example.invalid')",id);
  jdbc.update("INSERT INTO rya.productos(id,categoria_id,tipo_id,sku,nombre,slug,precio_base) VALUES (?,?,?,'FEEDBACK-TEST','Producto de prueba',?,10)",product,UUID.randomUUID(),UUID.randomUUID(),product.toString());
 }
 @Test void crearExigeSesionYLectorPublicoSigueDisponible()throws Exception{
  mvc.perform(post("/api/v1/productos/"+product+"/resenas").contentType("application/json").content("{\"calificacion\":5,\"comentario\":\"Opinion de prueba\"}")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/v1/ayuda/consultas").contentType("application/json").content("{\"asunto\":\"Pregunta\",\"mensaje\":\"Consulta de prueba\"}")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/v1/productos/"+product+"/resenas")).andExpect(status().isOk());
 }
 @Test void resenaUsaAutorFirmadoYQuedaPendienteSinDuplicarse()throws Exception{
  String body="{\"calificacion\":5,\"comentario\":\"Opinion de prueba\",\"usuarioId\":\""+other+"\",\"publicada\":true}";
  mvc.perform(post("/api/v1/productos/"+product+"/resenas").with(jwt().jwt(j->j.subject(user.toString()))).contentType("application/json").content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.estado").value("PENDIENTE"));
  assertEquals(user,jdbc.queryForObject("SELECT usuario_id FROM rya.resenas_producto WHERE producto_id=?",UUID.class,product));
  assertFalse(jdbc.queryForObject("SELECT publicada FROM rya.resenas_producto WHERE producto_id=?",Boolean.class,product));
  mvc.perform(post("/api/v1/productos/"+product+"/resenas").with(jwt().jwt(j->j.subject(user.toString()))).contentType("application/json").content(body)).andExpect(status().isConflict());
 }
 @Test void rechazaCalificacionesYMensajesInvalidos()throws Exception{
  mvc.perform(post("/api/v1/productos/"+product+"/resenas").with(jwt().jwt(j->j.subject(user.toString()))).contentType("application/json").content("{\"calificacion\":8,\"comentario\":\"Opinion de prueba\"}")).andExpect(status().isBadRequest());
  mvc.perform(post("/api/v1/ayuda/consultas").with(jwt().jwt(j->j.subject(user.toString()))).contentType("application/json").content("{\"asunto\":\"Hola\",\"mensaje\":\"x\"}")).andExpect(status().isBadRequest());
 }
 @Test void consultasPrivadasOcultanNotasInternasYPermitenRespuestaAdministrativa()throws Exception{
  UUID ticket=service.question(user,"Consulta técnica","Necesito ayuda con mi producto");
  service.answer(other,ticket,"Esta es la respuesta del equipo");
  jdbc.update("INSERT INTO rya.mensajes_ticket(id,ticket_id,autor_id,contenido,interno) VALUES (?,?,?,'Nota privada',true)",UUID.randomUUID(),ticket,other);
  assertEquals(2,((List<?>)service.questions(user,false).stream().filter(t->t.get("id").equals(ticket)).findFirst().orElseThrow().get("mensajes")).size());
  assertTrue(service.questions(other,false).stream().noneMatch(t->t.get("id").equals(ticket)));
  mvc.perform(get("/api/v1/admin/ayuda/consultas").with(jwt().jwt(j->j.subject(user.toString())))).andExpect(status().isForbidden());
  mvc.perform(post("/api/v1/admin/ayuda/consultas/"+ticket+"/respuesta").with(jwt().jwt(j->j.subject(other.toString()).claim("app_metadata",Map.of("role","admin")))).contentType("application/json").content("{\"mensaje\":\"Respuesta administrativa comprobada\"}")).andExpect(status().isOk());
 }
}
