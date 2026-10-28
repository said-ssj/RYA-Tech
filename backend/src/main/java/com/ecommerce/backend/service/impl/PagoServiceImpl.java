package com.ecommerce.backend.service.impl;

import com.ecommerce.backend.config.MercadoPagoProperties;
import com.ecommerce.backend.dto.ValidarCheckoutRequestDto;
import com.ecommerce.backend.dto.pago.*;
import com.ecommerce.backend.entity.*;
import com.ecommerce.backend.exception.BadRequestException;
import com.ecommerce.backend.exception.ConflictException;
import com.ecommerce.backend.exception.ResourceNotFoundException;
import com.ecommerce.backend.repository.*;
import com.ecommerce.backend.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Supplier;

@Service @RequiredArgsConstructor @Slf4j
public class PagoServiceImpl implements PagoService {
    private final MercadoPagoProperties config;
    private final MercadoPagoOrdersClient orders;
    private final TarjetasPagoService cards;
    private final MercadoPagoWebhookVerifier signatures;
    private final ObjectMapper mapper;
    private final PlatformTransactionManager transactionManager;
    private final jakarta.persistence.EntityManager entityManager;
    private final CheckoutService checkout;
    private final PedidoRepository pedidoRepository;
    private final PagoRepository pagoRepository;
    private final ProductoRepository productoRepository;
    private final ExistenciaRepository existenciaRepository;
    private final TarifaEnvioRepository tarifaEnvioRepository;
    private final MetodoPagoRepository metodoPagoRepository;
    private final CarritoRepository carritoRepository;
    private final ItemCarritoRepository itemCarritoRepository;
    private static final Set<String> FINAL_FAILED=Set.of("failed","canceled","cancelled","expired","rejected");
    private static final Set<String> METHODS=Set.of("MP_CARDS","MP_YAPE","MP_PAGOEFECTIVO");
    private <T> T tx(Supplier<T> task){return new TransactionTemplate(transactionManager).execute(status->task.get());}

    @Override @Transactional
    public IniciarPagoResponseDto iniciarPago(String sessionKey,UUID usuarioId,CrearPedidoPagoRequestDto request) {
        orders.requireCredentials();
        if(usuarioId==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Inicia sesión para pagar.");
        var validation=new ValidarCheckoutRequestDto();validation.setCliente(request.getCliente());validation.setEntrega(request.getEntrega());validation.setMetodoPagoId(request.getMetodoPagoId());
        checkout.validar(sessionKey,usuarioId,validation);
        // A pending/uncertain attempt must never be bypassed by creating another local order.
        for(var prior:pedidoRepository.findByUsuarioIdOrderByCreatedAtDesc(usuarioId)){
            if(!pendingOrder(prior))continue;
            var attempts=pagoRepository.findByPedidoIdOrderByCreatedAtDesc(prior.getId());
            if(attempts.stream().anyMatch(p->!FINAL_FAILED.contains(p.getEstado())))
                throw new ConflictException("Ya existe un pago pendiente para "+prior.getNumeroPedido()+". Consulta su estado en Mis compras antes de iniciar otro cobro.");
        }
        var cart=carritoRepository.findFirstByUsuarioIdAndEstadoAndIsDeletedFalseOrderByCreatedAtDesc(usuarioId,"ABIERTO")
            .orElseThrow(()->new BadRequestException("No tienes un carrito activo."));
        var items=itemCarritoRepository.findByCarritoIdAndIsDeletedFalseOrderByCreatedAtAsc(cart.getId());
        if(items.isEmpty())throw new BadRequestException("El carrito está vacío.");
        var method=metodoPagoRepository.findByIdAndActivoTrueAndIsDeletedFalse(request.getMetodoPagoId())
            .orElseThrow(()->new BadRequestException("Método de pago no disponible."));
        if(!METHODS.contains(method.getCodigo()))throw new BadRequestException("Ese método no está integrado con Orders.");
        var shipping=tarifaEnvioRepository.findByIdAndIsDeletedFalse(request.getEntrega().getTarifaEnvioId())
            .orElseThrow(()->new BadRequestException("Tarifa de envío no disponible."));
        com.ecommerce.backend.service.EntregaRules.validar(shipping, request.getEntrega());
        var snapshots=new ArrayList<PedidoItem>();var subtotal=BigDecimal.ZERO;
        for(var item:items){
            var product=productoRepository.findConDetallesPorId(item.getProducto().getId())
                .orElseThrow(()->new BadRequestException("Producto no disponible."));
            validateProduct(product,item.getCantidad());
            var amount=product.getPrecioVenta().multiply(BigDecimal.valueOf(item.getCantidad()));
            subtotal=subtotal.add(amount);
            snapshots.add(PedidoItem.builder().productoId(product.getId()).sku(product.getSku()).nombreProducto(product.getNombre())
                .precioUnitario(product.getPrecioVenta()).cantidad(item.getCantidad()).subtotal(amount).build());
        }
        var total=subtotal.add(shipping.getCosto());
        if(total.signum()<=0)throw new BadRequestException("El importe a pagar debe ser mayor que cero.");
        var customer=request.getCliente();var delivery=request.getEntrega();
        var pedido=Pedido.builder().numeroPedido("RYA-"+UUID.randomUUID().toString().replace("-","").substring(0,24))
            .usuarioId(usuarioId).sessionKey(sessionKey).estado("PENDIENTE").moneda("PEN")
            .subtotal(subtotal).costoEnvio(shipping.getCosto()).total(total)
            .tipoDocumento(customer.getTipoDocumento()).numeroDocumento(customer.getNumeroDocumento()).nombreCompleto(customer.getNombre())
            .email(customer.getEmail()).telefono(customer.getTelefono()).tarifaEnvioId(shipping.getId())
            .direccionEnvio(com.ecommerce.backend.service.EntregaRules.recojo(shipping) ? shipping.getDireccionRecojo() : delivery.getDireccion()).referenciaEnvio(delivery.getReferencia())
            .modalidadEntrega(java.util.Objects.toString(shipping.getModalidad(), "DOMICILIO")).estadoEntrega("PENDIENTE")
            .entregaSnapshot(com.ecommerce.backend.service.EntregaRules.descripcion(shipping, delivery) + " · " + java.util.Objects.toString(shipping.getNombreServicio(), "Servicio de entrega") + (shipping.getPlazoDias() == null ? " · Plazo por confirmar" : " · Plazo: " + shipping.getPlazoDias() + " días"))
            .metodoPagoCodigo(method.getCodigo()).metodoPagoSnapshot(method.getNombre()).build();
        snapshots.forEach(item->item.setPedido(pedido));pedido.setItems(snapshots);pedidoRepository.save(pedido);
        return IniciarPagoResponseDto.builder().pedidoId(pedido.getId()).numeroPedido(pedido.getNumeroPedido())
            .metodoPagoCodigo(method.getCodigo()).total(total).moneda("PEN").publicKey(config.getPublicKey()).build();
    }

    private void validateProduct(Producto product,int quantity){
        if(quantity<1||!Boolean.TRUE.equals(product.getActivo())||!Boolean.TRUE.equals(product.getVendible())
            ||Boolean.TRUE.equals(product.getIsDeleted())||product.getPrecioVenta()==null)
            throw new BadRequestException("Un producto del pedido ya no está disponible.");
        var stock=existenciaRepository.findStockDisponiblePorProductoId(product.getId());
        if(stock==null||stock<quantity)throw new BadRequestException("Stock insuficiente para "+product.getNombre()+".");
    }

    private Pedido owned(UUID id,UUID user,boolean lock){
        var pedido=(lock?pedidoRepository.findForPaymentUpdate(id):pedidoRepository.findById(id))
            .orElseThrow(()->new ResourceNotFoundException("Pedido no encontrado."));
        if(user==null||!user.equals(pedido.getUsuarioId()))throw new ResourceNotFoundException("Pedido no encontrado.");
        return pedido;
    }
    private record Attempt(UUID id,UUID pedidoId,Map<String,Object> body,String orderId){}
    @Override
    public ResultadoPagoDto procesarCargo(String sessionKey,UUID usuarioId,ProcesarCargoRequestDto request){
        orders.requireCredentials();
        var attempt=tx(()->{
            var pedido=owned(request.getPedidoId(),usuarioId,true);
            var old=pagoRepository.findByPedidoIdOrderByCreatedAtDesc(pedido.getId());
            if(!pendingOrder(pedido)||old.stream().anyMatch(p->!FINAL_FAILED.contains(p.getEstado())))
                return new Attempt(null,pedido.getId(),null,old.isEmpty()?null:old.getFirst().getMpOrderId());
            for(var item:pedido.getItems()){
                var product=productoRepository.findConDetallesPorId(item.getProductoId()).orElseThrow(()->new BadRequestException("Producto no disponible."));
                validateProduct(product,item.getCantidad());
                if(product.getPrecioVenta().compareTo(item.getPrecioUnitario())!=0)throw new ConflictException("Cambió un precio. Revisa y valida nuevamente tu compra.");
            }
            var payment=Pago.builder().pedido(pedido).pasarela("MERCADO_PAGO_ORDERS").monto(pedido.getTotal())
                .moneda(pedido.getMoneda()).estado("processing").paymentMethodId(request.getPaymentMethodId()).build();
            var body=body(pedido,request);
            pagoRepository.saveAndFlush(payment);
            body.put("external_reference",payment.getId().toString());
            return new Attempt(payment.getId(),pedido.getId(),body,null);
        });
        if(attempt.id()==null)return consultarPago(attempt.pedidoId(),usuarioId);
        // The attempt is committed before the network call. An interrupted response cannot cause a new charge.
        try{
            var response=orders.create(attempt.id().toString(),attempt.body());
            return tx(()->apply(attempt.id(),response));
        }catch(Exception ex){
            boolean definite=ex instanceof RestClientResponseException http && http.getStatusCode().is4xxClientError()
                && !Set.of(408,409,429).contains(http.getStatusCode().value());
            log.warn("Orders: respuesta {} para intento {} tipo={} http={}",definite?"rechazada":"sin confirmar",attempt.id(),ex.getClass().getSimpleName(),ex instanceof RestClientResponseException http?http.getStatusCode().value():0);
            return tx(()->{
                var p=pagoRepository.findById(attempt.id()).orElseThrow();
                pedidoRepository.findForPaymentUpdate(p.getPedido().getId());
                entityManager.refresh(p);
                if(!"processed".equals(p.getEstado())){
                    p.setEstado(definite?"failed":"unknown");
                    p.setMensajeRespuesta(definite?"Mercado Pago rechazó la solicitud. Revisa las credenciales y los datos de pago."
                        :"El resultado está pendiente de confirmación. No vuelvas a pagar; consulta el estado de este pedido.");
                    pagoRepository.save(p);
                }
                return result(p.getPedido(),p);
            });
        }
    }

    private Map<String,Object> body(Pedido pedido,ProcesarCargoRequestDto request){
        String method=request.getPaymentMethodId(), type=request.getPaymentTypeId();
        TarjetasPagoService.OwnedCard saved=null;
        if(request.getSavedCardId()!=null){
            if(!"MP_CARDS".equals(pedido.getMetodoPagoCodigo()))throw new BadRequestException("Este pedido no utiliza tarjetas.");
            saved=cards.owned(pedido.getUsuarioId(),request.getSavedCardId());
            method=saved.card().paymentMethodId();type=saved.card().paymentTypeId();
        }
        if(method==null||!method.matches("[a-zA-Z0-9_]{1,64}"))throw new BadRequestException("Falta un medio de pago válido.");
        if("MP_YAPE".equals(pedido.getMetodoPagoCodigo())){
            if(!"yape".equals(method))throw new BadRequestException("Este pedido requiere Yape.");
            type="debit_card";
        }else if("MP_PAGOEFECTIVO".equals(pedido.getMetodoPagoCodigo())){
            if(!"pagoefectivo_atm".equals(method))throw new BadRequestException("Este pedido requiere PagoEfectivo.");
            type="atm";
        }else if(!"MP_CARDS".equals(pedido.getMetodoPagoCodigo())||!Set.of("credit_card","debit_card").contains(type==null?"":type)){
            throw new BadRequestException("Selecciona una tarjeta de crédito o débito válida.");
        }
        var pm=new LinkedHashMap<String,Object>();pm.put("id",method);pm.put("type",type);
        if(!"atm".equals(type)){
            if(request.getToken()==null||request.getToken().isBlank()||request.getToken().length()>512)throw new BadRequestException("Falta el token seguro del medio de pago.");
            pm.put("token",request.getToken());
            if(!"yape".equals(method)){
                int installments=request.getInstallments()==null?1:request.getInstallments();
                if(installments<1||installments>48)throw new BadRequestException("Número de cuotas inválido.");
                pm.put("installments",installments);
            }
        }
        var payer=new LinkedHashMap<String,Object>();payer.put("email",request.getEmail());
        if(saved!=null)payer.put("customer_id",saved.customerId());
        String docType=request.getIdentificationType(),doc=request.getIdentificationNumber();
        if(docType!=null&&doc!=null&&!doc.isBlank()){
            if(!docType.matches("[A-Za-z_]{1,20}")||!doc.matches("[A-Za-z0-9]{1,20}"))throw new BadRequestException("Identificación del pagador inválida.");
            payer.put("identification",Map.of("type",docType,"number",doc));
        }
        var body=new LinkedHashMap<String,Object>();body.put("type","online");body.put("processing_mode","automatic");
        body.put("total_amount",money(pedido.getTotal()));body.put("payer",payer);
        body.put("transactions",Map.of("payments",List.of(Map.of("amount",money(pedido.getTotal()),"payment_method",pm))));
        return body;
    }
    private static boolean pendingOrder(Pedido pedido){return Set.of("PENDIENTE","PENDIENTE_PAGO").contains(pedido.getEstado());}
    private static String money(BigDecimal n){return n.setScale(2,RoundingMode.UNNECESSARY).toPlainString();}
    private static String text(JsonNode node,String field){return node.path(field).asText("");}
    private static boolean equalAmount(JsonNode node,BigDecimal expected){
        try{return !node.isMissingNode()&&!node.isNull()&&new BigDecimal(node.asText()).compareTo(expected)==0;}catch(Exception ex){return false;}
    }
    private ResultadoPagoDto apply(UUID attemptId,JsonNode order){
        var payment=pagoRepository.findById(attemptId).orElseThrow(()->new ResourceNotFoundException("Intento de pago no encontrado."));
        var pedido=pedidoRepository.findForPaymentUpdate(payment.getPedido().getId()).orElseThrow();
        entityManager.refresh(payment);
        if(order==null||!text(order,"external_reference").equals(attemptId.toString())
            ||!text(order,"id").matches("[A-Za-z0-9_-]{1,64}")||!text(order,order.has("currency")?"currency":"currency_id").equals(pedido.getMoneda())
            ||!equalAmount(order.path("total_amount"),payment.getMonto())
            ||(order.has("live_mode")&&(!order.path("live_mode").isBoolean()||order.path("live_mode").asBoolean()!=config.isLiveMode())))
            throw new BadRequestException("No coinciden los datos verificados de la order.");
        // Orders uses currency and may omit live_mode. Test order IDs must never approve live purchases.
        if(!order.has("live_mode") && (config.isLiveMode()==text(order,"id").startsWith("ORDTST")))
            throw new BadRequestException("El entorno de la order no corresponde a esta integración.");
        if(payment.getMpOrderId()!=null&&!payment.getMpOrderId().equals(text(order,"id")))throw new BadRequestException("La order no corresponde a este intento.");
        var payments=order.path("transactions").path("payments");
        if(!payments.isArray()||payments.size()!=1||!equalAmount(payments.get(0).path("amount"),payment.getMonto()))
            throw new BadRequestException("La transacción de la order no coincide con el pedido.");
        var transaction=payments.get(0);String status=text(order,"status"), detail=text(order,"status_detail");
        if(status.isBlank()||status.length()>30||detail.length()>100)throw new BadRequestException("Estado de order inválido.");
        boolean paid="processed".equals(status)&&"accredited".equals(detail)
            &&"processed".equals(text(transaction,"status"))&&"accredited".equals(text(transaction,"status_detail"));
        if(paid&&((order.has("total_paid_amount")&&!equalAmount(order.path("total_paid_amount"),payment.getMonto()))
            ||(transaction.has("paid_amount")&&!equalAmount(transaction.path("paid_amount"),payment.getMonto()))))
            throw new BadRequestException("El importe acreditado no coincide con el pedido.");
        // A delayed synchronous response cannot regress a webhook-confirmed payment.
        if("processed".equals(payment.getEstado())&&!paid&&!Set.of("refunded","charged_back").contains(status))return result(pedido,payment);
        payment.setMpOrderId(text(order,"id"));payment.setMpPaymentId(text(transaction,"id"));
        payment.setEstado(paid?"processed":status);payment.setStatusDetail(detail);
        payment.setPaymentMethodId(text(transaction.path("payment_method"),"id"));
        payment.setPaymentTypeId(text(transaction.path("payment_method"),"type"));
        String ticket=text(transaction.path("payment_method"),"ticket_url");
        payment.setInstruccionesUrl(safeTicket(ticket));payment.setReferenciaPago(text(transaction.path("payment_method"),"reference"));
        payment.setMensajeRespuesta(paid?"Pago aprobado y acreditado por Mercado Pago."
            :"refunded".equals(status)?"Mercado Pago confirmó el reembolso de esta order."
            :FINAL_FAILED.contains(status)?"El pago no se completó. Puedes revisar los datos e intentar nuevamente."
            :"Tu pago está pendiente. No se marcará como pagado hasta recibir la confirmación de Mercado Pago.");
        // Flush verified payment evidence before the database validates the order transition.
        pagoRepository.saveAndFlush(payment);
        if(paid&&pendingOrder(pedido)){
            pedido.setEstado("PAGADO");clearPurchasedItems(pedido);pedidoRepository.save(pedido);
        }else if("refunded".equals(status)&&"PAGADO".equals(pedido.getEstado())){
            pedido.setEstado("REEMBOLSADO");pedidoRepository.save(pedido);
        }
        pagoRepository.save(payment);return result(pedido,payment);
    }
    private static String safeTicket(String url){
        if(url==null||url.isBlank())return null;
        try{var uri=java.net.URI.create(url);String host=uri.getHost();
            return "https".equals(uri.getScheme())&&uri.getUserInfo()==null&&host!=null&&
                (host.equals("mercadopago.com.pe")||host.endsWith(".mercadopago.com.pe"))?url:null;
        }catch(Exception ex){return null;}
    }
    private void clearPurchasedItems(Pedido pedido){
        carritoRepository.findFirstByUsuarioIdAndEstadoAndIsDeletedFalseOrderByCreatedAtDesc(pedido.getUsuarioId(),"ABIERTO").ifPresent(cart->{
            var items=itemCarritoRepository.findByCarritoIdAndIsDeletedFalseOrderByCreatedAtAsc(cart.getId());
            for(var item:items)if(pedido.getItems().stream().anyMatch(p->p.getProductoId().equals(item.getProducto().getId())&&p.getCantidad().equals(item.getCantidad())))item.setIsDeleted(true);
            itemCarritoRepository.saveAll(items);
        });
    }
    private ResultadoPagoDto result(Pedido pedido,Pago payment){
        boolean paid=Set.of("PAGADO","PREPARANDO","ENVIADO","ENTREGADO").contains(pedido.getEstado());
        String status=payment==null?"not_started":payment.getEstado();
        return ResultadoPagoDto.builder().exitoso(paid).pedidoId(pedido.getId()).numeroPedido(pedido.getNumeroPedido()).estadoPedido(pedido.getEstado())
            .estadoPago(status).pendiente(!paid&&payment!=null&&!FINAL_FAILED.contains(status)&&!Set.of("refunded","charged_back").contains(status))
            .mpOrderId(payment==null?null:payment.getMpOrderId()).mpPaymentId(payment==null?null:payment.getMpPaymentId())
            .mensaje(payment==null?"Todavía no se ha enviado un pago.":payment.getMensajeRespuesta())
            .instruccionesUrl(payment==null?null:payment.getInstruccionesUrl()).referenciaPago(payment==null?null:payment.getReferenciaPago())
            .monto(pedido.getTotal()).moneda(pedido.getMoneda()).build();
    }
    @Override public ResultadoPagoDto consultarPago(UUID pedidoId,UUID user){
        var snapshot=tx(()->{
            var pedido=owned(pedidoId,user,false);var all=pagoRepository.findByPedidoIdOrderByCreatedAtDesc(pedidoId);
            return new Attempt(all.isEmpty()?null:all.getFirst().getId(),pedidoId,null,all.isEmpty()?null:all.getFirst().getMpOrderId());
        });
        if(snapshot.id()!=null&&snapshot.orderId()==null){
            try{
                var created=tx(()->pagoRepository.findById(snapshot.id()).orElseThrow().getCreatedAt());
                var matches=orders.search(snapshot.id(),created);
                var data=matches==null?null:matches.path("data");
                if(data!=null&&data.isArray()){
                    var exact=new ArrayList<JsonNode>();for(var candidate:data)if(snapshot.id().toString().equals(text(candidate,"external_reference")))exact.add(candidate);
                    if(exact.size()==1){var verified=orders.get(text(exact.getFirst(),"id"));return tx(()->apply(snapshot.id(),verified));}
                }
            }catch(Exception ex){log.warn("Orders: reconciliación sin confirmar para intento {} tipo={}",snapshot.id(),ex.getClass().getSimpleName());}
        }
        if(snapshot.orderId()!=null){
            try{var response=orders.get(snapshot.orderId());return tx(()->apply(snapshot.id(),response));}
            catch(Exception ex){log.warn("Orders: consulta sin confirmar para pedido {}",pedidoId);}
        }
        return tx(()->{var pedido=owned(pedidoId,user,false);var all=pagoRepository.findByPedidoIdOrderByCreatedAtDesc(pedidoId);return result(pedido,all.isEmpty()?null:all.getFirst());});
    }
    @Override public void procesarWebhook(String payload,String signature,String requestId,String dataId){
        signatures.verify(signature,requestId,dataId);
        try{
            JsonNode event=mapper.readTree(payload);
            if(!"order".equals(text(event,"type")))return;
            if(!dataId.equalsIgnoreCase(text(event.path("data"),"id")))throw new BadRequestException("El ID de la notificación no coincide con la firma.");
            var response=orders.get(dataId);UUID attemptId=UUID.fromString(text(response,"external_reference"));
            tx(()->{if(pagoRepository.findById(attemptId).isEmpty())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Intento todavía no disponible.");return apply(attemptId,response);});
        }catch(org.springframework.web.server.ResponseStatusException|BadRequestException ex){throw ex;}
        catch(Exception ex){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"No se pudo verificar la order; reintenta la notificación.");}
    }
}
