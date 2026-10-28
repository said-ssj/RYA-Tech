package com.ecommerce.backend.service;
import com.ecommerce.backend.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;import java.math.BigDecimal;import java.time.OffsetDateTime;
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class AdminPricingService {
 private final JdbcTemplate jdbc;private final AdminOperationsService audit;
 public record Promotion(UUID id,String nombre,BigDecimal descuentoPorcentaje,OffsetDateTime inicio,OffsetDateTime fin,boolean activa,List<String> skus,String version){}
 public List<Promotion> list(){return jdbc.query("SELECT id,nombre,descuento_porcentaje,inicio_at,fin_at,activa,updated_at FROM rya.promociones WHERE is_deleted=false ORDER BY updated_at DESC LIMIT 200",(rs,n)->new Promotion(rs.getObject("id",UUID.class),rs.getString("nombre"),rs.getBigDecimal("descuento_porcentaje"),rs.getObject("inicio_at",OffsetDateTime.class),rs.getObject("fin_at",OffsetDateTime.class),rs.getBoolean("activa"),jdbc.queryForList("SELECT p.sku FROM rya.promociones_productos pp JOIN rya.productos p ON p.id=pp.producto_id WHERE pp.promocion_id=? AND pp.is_deleted=false ORDER BY p.sku",String.class,rs.getObject("id",UUID.class)),rs.getObject("updated_at",OffsetDateTime.class).toString()));}
 public Map<String,Object> product(String sku){var rows=jdbc.queryForList("SELECT id,sku,nombre,precio_base FROM rya.productos WHERE sku=? AND is_deleted=false",sku.trim());if(rows.size()!=1)throw new ResourceNotFoundException("No se encontró un producto vigente con ese SKU exacto.");return rows.getFirst();}
 @Transactional public void price(String sku,BigDecimal expected,BigDecimal amount,UUID actor){
  var row=product(sku);int count=jdbc.update("UPDATE rya.productos SET precio_base=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND precio_base=? AND is_deleted=false",amount,row.get("id"),expected);
  if(count!=1)throw new ConflictException("El precio cambió. Vuelve a consultar el producto antes de guardar.");audit.audit(actor,"ofertas",row.get("id").toString(),"PRECIO_BASE","Anterior: "+expected+"; nuevo: "+amount);
 }
 @Transactional public UUID save(Promotion data,UUID actor){
  if(!data.fin().isAfter(data.inicio()))throw new BadRequestException("La fecha final debe ser posterior al inicio.");
  var skus=new TreeSet<String>();for(String sku:data.skus()){if(sku==null||sku.isBlank())throw new BadRequestException("Hay un SKU vacío.");skus.add(sku.trim());}
  if(skus.size()!=data.skus().size())throw new BadRequestException("El lote contiene SKU repetidos.");
  var products=new ArrayList<Map<String,Object>>();for(String sku:skus)products.add(product(sku));
  UUID id=data.id()==null?UUID.randomUUID():data.id();
  if(data.id()!=null){
   var current=jdbc.query("SELECT updated_at FROM rya.promociones WHERE id=? AND is_deleted=false FOR UPDATE",(rs,n)->rs.getObject(1,OffsetDateTime.class).toString(),id);
   if(current.isEmpty())throw new ResourceNotFoundException("Promoción no encontrada.");
   if(!current.getFirst().equals(data.version()))throw new ConflictException("La promoción cambió. Actualiza el listado antes de editar.");
   var previous=new TreeSet<>(jdbc.queryForList("SELECT p.sku FROM rya.promociones_productos pp JOIN rya.productos p ON p.id=pp.producto_id WHERE pp.promocion_id=? AND pp.is_deleted=false",String.class,id));
   if(!previous.equals(skus))throw new BadRequestException("Los productos de una promoción existente se conservan. Crea otra promoción para cambiar los SKU.");
  }
  // Lock products in a stable order so concurrent promotions cannot bypass overlap checks.
  for(var p:products)jdbc.queryForList("SELECT id FROM rya.productos WHERE id=? FOR UPDATE",p.get("id"));
  if(data.activa())for(var p:products){
   Long overlaps=jdbc.queryForObject("SELECT count(*) FROM rya.promociones o JOIN rya.promociones_productos pp ON pp.promocion_id=o.id WHERE pp.producto_id=? AND pp.is_deleted=false AND o.is_deleted=false AND o.activa=true AND o.id<>? AND (o.inicio_at IS NULL OR o.inicio_at<?) AND (o.fin_at IS NULL OR o.fin_at>?)",Long.class,p.get("id"),id,data.fin(),data.inicio());
   if(overlaps>0)throw new ConflictException("El producto "+p.get("sku")+" ya tiene una promoción activa en ese periodo.");
  }
  if(data.id()==null){jdbc.update("INSERT INTO rya.promociones(id,nombre,descuento_porcentaje,inicio_at,fin_at,activa,is_deleted) VALUES (?,?,?,?,?,?,false)",id,data.nombre().trim(),data.descuentoPorcentaje(),data.inicio(),data.fin(),data.activa());
   for(var p:products)jdbc.update("INSERT INTO rya.promociones_productos(id,promocion_id,producto_id,precio_anterior,is_deleted) VALUES (?,?,?,?,false)",UUID.randomUUID(),id,p.get("id"),p.get("precio_base"));
  }else jdbc.update("UPDATE rya.promociones SET nombre=?,descuento_porcentaje=?,inicio_at=?,fin_at=?,activa=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",data.nombre().trim(),data.descuentoPorcentaje(),data.inicio(),data.fin(),data.activa(),id);
  audit.audit(actor,"ofertas",id.toString(),data.activa()?"PROMOCION_GUARDADA":"PROMOCION_DESACTIVADA",data.nombre());return id;
 }
}
