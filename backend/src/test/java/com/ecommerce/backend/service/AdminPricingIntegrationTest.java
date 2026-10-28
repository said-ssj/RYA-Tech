package com.ecommerce.backend.service;
import org.junit.jupiter.api.*;import org.springframework.beans.factory.annotation.Autowired;import org.springframework.boot.test.context.SpringBootTest;import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;import org.springframework.test.context.ActiveProfiles;import org.springframework.transaction.annotation.Transactional;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.test.web.servlet.MockMvc;
import com.ecommerce.backend.repository.ProductoRepository;import com.ecommerce.backend.mapper.ProductoMapper;import com.ecommerce.backend.exception.*;import jakarta.persistence.EntityManager;
import java.util.*;import java.time.OffsetDateTime;import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc @Transactional
class AdminPricingIntegrationTest {
 @Autowired AdminPricingService admin;@Autowired OfertaService offers;@Autowired ProductoRepository products;@Autowired JdbcTemplate jdbc;@Autowired EntityManager em;@Autowired ProductoMapper mapper;@Autowired MockMvc mvc;
 UUID product=UUID.randomUUID(),actor=UUID.randomUUID();String sku="PRICE-"+product;
 @BeforeEach void setup(){
  UUID category=UUID.randomUUID(),type=UUID.randomUUID();
  jdbc.update("INSERT INTO rya.categorias(id,nombre,slug) VALUES (?,'Categoria precios',?)",category,category.toString());
  jdbc.update("INSERT INTO rya.tipos_producto(id,codigo,descripcion) VALUES (?,'HARDWARE','Hardware')",type);
  jdbc.update("INSERT INTO rya.productos(id,categoria_id,tipo_id,sku,nombre,slug,precio_base) VALUES (?,?,?,?,'Producto precios',?,1000)",product,category,type,sku,product.toString());
 }
 AdminPricingService.Promotion promotion(UUID id,boolean active,int begin,int end,String version){return new AdminPricingService.Promotion(id,"Oferta comprobada",new BigDecimal("10"),OffsetDateTime.now().plusDays(begin),OffsetDateTime.now().plusDays(end),active,List.of(sku),version);}
 BigDecimal price(){em.clear();return products.findById(product).orElseThrow().getPrecioVenta();}
 @Test void precioVigenteCoincideConOfertasYCatalogoSinModificarPrecioBase(){
  UUID id=admin.save(promotion(null,true,-1,1,null),actor);assertEquals(0,new BigDecimal("900.00").compareTo(price()));
  var entity=products.findById(product).orElseThrow();assertEquals(0,new BigDecimal("1000").compareTo(entity.getPrecioBase()));assertEquals(0,entity.getPrecioVenta().compareTo(mapper.toResumenDto(entity,null,1).getPrecioBase()));
  var offer=offers.listar(null,null,null,false,null,"precio-asc",0,100).content().stream().filter(x->x.productoId().equals(product)).findFirst().orElseThrow();assertEquals(0,price().compareTo(offer.precioActual()));
  var existing=admin.list().stream().filter(x->x.id().equals(id)).findFirst().orElseThrow();admin.save(promotion(id,false,-1,1,existing.version()),actor);assertEquals(0,new BigDecimal("1000").compareTo(price()));
 }
 @Test void promocionesFuturasYVencidasNoAlteranElPrecioYNoSeAcumulan(){
  admin.save(promotion(null,true,2,3,null),actor);admin.save(promotion(null,true,-3,-2,null),actor);assertEquals(0,new BigDecimal("1000").compareTo(price()));
  admin.save(promotion(null,true,-1,1,null),actor);assertThrows(ConflictException.class,()->admin.save(promotion(null,true,0,2,null),actor));assertEquals(0,new BigDecimal("900").compareTo(price()));
 }
 @Test void skuInexistenteNoGuardaMedioLoteYPrecioExigeValorAnterior(){
  long count=jdbc.queryForObject("SELECT count(*) FROM rya.promociones",Long.class);
  var p=promotion(null,true,-1,1,null);assertThrows(ResourceNotFoundException.class,()->admin.save(new AdminPricingService.Promotion(null,p.nombre(),p.descuentoPorcentaje(),p.inicio(),p.fin(),true,List.of(sku,"NO-EXISTE"),null),actor));assertEquals(count,jdbc.queryForObject("SELECT count(*) FROM rya.promociones",Long.class));
  admin.price(sku,new BigDecimal("1000"),new BigDecimal("1100"),actor);assertThrows(ConflictException.class,()->admin.price(sku,new BigDecimal("1000"),new BigDecimal("1200"),actor));assertEquals(0,new BigDecimal("1100").compareTo(price()));
 }
 @Test void ofertasAdministrativasRequierenRolAdmin()throws Exception{
  mvc.perform(get("/api/v1/admin/ofertas")).andExpect(status().isUnauthorized());mvc.perform(get("/api/v1/admin/ofertas").with(jwt())).andExpect(status().isForbidden());
  mvc.perform(get("/api/v1/admin/ofertas").with(jwt().jwt(j->j.subject(actor.toString()).claim("app_metadata",Map.of("role","admin"))))).andExpect(status().isOk());
 }
}
