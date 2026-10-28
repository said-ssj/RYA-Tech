package com.ecommerce.backend.service;
import com.ecommerce.backend.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service @RequiredArgsConstructor
public class CustomerFeedbackService {
 private final JdbcTemplate jdbc;
 private void account(UUID user){
  if(jdbc.queryForList("SELECT id FROM rya.usuarios WHERE id=? AND is_deleted=false FOR UPDATE",user).isEmpty())throw new BadRequestException("Completa los datos de tu cuenta antes de enviar el mensaje.");
 }
 @Transactional public UUID review(UUID user,UUID product,int rating,String comment){
  account(user);
  if(rating<1||rating>5||comment==null||comment.trim().length()<10||comment.length()>2000)throw new BadRequestException("Escribe una reseña de 10 a 2000 caracteres y una calificación de 1 a 5.");
  if(jdbc.queryForObject("SELECT count(*) FROM rya.productos WHERE id=? AND is_deleted=false AND activo=true",Long.class,product)==0)throw new ResourceNotFoundException("Producto no disponible.");
  if(jdbc.queryForObject("SELECT count(*) FROM rya.resenas_producto WHERE usuario_id=? AND producto_id=? AND is_deleted=false",Long.class,user,product)>0)throw new ConflictException("Ya enviaste una reseña para este producto; puede estar pendiente de moderación.");
  UUID id=UUID.randomUUID();jdbc.update("INSERT INTO rya.resenas_producto(id,usuario_id,producto_id,calificacion,comentario,publicada,estado_moderacion,is_deleted) VALUES (?,?,?,?,?,false,'PENDIENTE',false)",id,user,product,rating,comment.trim());return id;
 }
 @Transactional public UUID question(UUID user,String subject,String message){
  account(user);if(subject==null||subject.trim().length()<5||subject.length()>220||message==null||message.trim().length()<10||message.length()>5000)throw new BadRequestException("Revisa el asunto y el contenido de tu consulta.");UUID id=UUID.randomUUID();jdbc.update("INSERT INTO rya.tickets_soporte(id,usuario_id,asunto,estado,prioridad,is_deleted) VALUES (?,?,?,'ABIERTO','MEDIA',false)",id,user,subject.trim());
  jdbc.update("INSERT INTO rya.mensajes_ticket(id,ticket_id,autor_id,contenido,interno,is_deleted) VALUES (?,?,?,?,false,false)",UUID.randomUUID(),id,user,message.trim());return id;
 }
 @Transactional(readOnly=true) public List<Map<String,Object>> questions(UUID user,boolean admin){
  var tickets=admin?jdbc.queryForList("SELECT id,asunto,estado,created_at FROM rya.tickets_soporte WHERE is_deleted=false ORDER BY created_at DESC LIMIT 100"):
    jdbc.queryForList("SELECT id,asunto,estado,created_at FROM rya.tickets_soporte WHERE usuario_id=? AND is_deleted=false ORDER BY created_at DESC LIMIT 30",user);
  for(var ticket:tickets)ticket.put("mensajes",jdbc.queryForList("SELECT m.contenido,m.created_at,(m.autor_id=t.usuario_id) AS cliente FROM rya.mensajes_ticket m JOIN rya.tickets_soporte t ON t.id=m.ticket_id WHERE m.ticket_id=? AND m.is_deleted=false AND m.interno=false ORDER BY m.created_at,m.id",ticket.get("id")));
  return tickets;
 }
 @Transactional public void answer(UUID admin,UUID ticket,String text){
  account(admin);if(text==null||text.trim().length()<10||text.length()>5000)throw new BadRequestException("La respuesta debe tener entre 10 y 5000 caracteres.");if(jdbc.queryForObject("SELECT count(*) FROM rya.tickets_soporte WHERE id=? AND is_deleted=false",Long.class,ticket)==0)throw new ResourceNotFoundException("Consulta no encontrada.");
  jdbc.update("INSERT INTO rya.mensajes_ticket(id,ticket_id,autor_id,contenido,interno,is_deleted) VALUES (?,?,?,?,false,false)",UUID.randomUUID(),ticket,admin,text.trim());
 }
}
