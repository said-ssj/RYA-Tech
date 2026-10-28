import {TestBed} from '@angular/core/testing';import {provideHttpClient} from '@angular/common/http';import {provideHttpClientTesting,HttpTestingController} from '@angular/common/http/testing';import {beforeEach,afterEach,describe,it,expect} from 'vitest';import {PricingAdmin} from './pricing-admin';
describe('Precios y promociones administrativas',()=>{
 let fixture:any,c:any,http:HttpTestingController;
 beforeEach(()=>{TestBed.configureTestingModule({imports:[PricingAdmin],providers:[provideHttpClient(),provideHttpClientTesting()]});http=TestBed.inject(HttpTestingController);fixture=TestBed.createComponent(PricingAdmin);c=fixture.componentInstance;fixture.detectChanges();http.expectOne('/api/v1/admin/ofertas').flush([]);});
 afterEach(()=>{http.verify();TestBed.resetTestingModule();});
 it('actualiza el SKU exacto con el precio anterior para detectar cambios concurrentes',()=>{
  c.sku='CPU-REAL';c.findProduct();http.expectOne(r=>r.url==='/api/v1/admin/ofertas/producto'&&r.params.get('sku')==='CPU-REAL').flush({id:'p1',sku:'CPU-REAL',nombre:'CPU',precio_base:1000});c.amount=950;c.price();const r=http.expectOne('/api/v1/admin/ofertas/precio');expect(r.request.method).toBe('PUT');expect(r.request.body).toEqual({sku:'CPU-REAL',precioAnterior:1000,precio:950});r.flush(null);expect(c.product().precio_base).toBe(950);
 });
 it('edita y desactiva manteniendo productos, versión y horario de Perú',()=>{
  c.edit({id:'offer1',nombre:'Oferta',descuentoPorcentaje:10,skus:['CPU-REAL'],inicio:'2026-10-28T15:00:00Z',fin:'2026-10-29T15:00:00Z',activa:true,version:'v1'});expect(c.form.inicio).toBe('2026-10-28T10:00');c.form.activa=false;c.save();const r=http.expectOne('/api/v1/admin/ofertas');expect(r.request.body).toMatchObject({id:'offer1',activa:false,version:'v1',skus:['CPU-REAL'],inicio:'2026-10-28T10:00:00-05:00'});r.flush({id:'offer1'});http.expectOne('/api/v1/admin/ofertas').flush([]);expect(c.editing()).toBe(false);
 });
 it('mantiene el editor si el servidor rechaza una promoción superpuesta',()=>{
  c.edit();Object.assign(c.form,{nombre:'Oferta',inicio:'2026-10-28T10:00',fin:'2026-10-29T10:00',skuText:'CPU-REAL'});c.save();http.expectOne('/api/v1/admin/ofertas').flush({message:'El producto ya tiene una promoción activa'},{status:409,statusText:'Conflict'});expect(c.editing()).toBe(true);expect(c.error()).toContain('ya tiene');expect(c.message()).toBe('');
 });
});
