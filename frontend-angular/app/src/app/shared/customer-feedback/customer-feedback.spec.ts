import {TestBed} from '@angular/core/testing';
import {provideHttpClient} from '@angular/common/http';
import {provideHttpClientTesting,HttpTestingController} from '@angular/common/http/testing';
import {provideRouter} from '@angular/router';
import {signal} from '@angular/core';
import {afterEach,beforeEach,describe,it,expect} from 'vitest';
import {AuthService} from '../../core/auth/auth.service';
import {CustomerFeedback} from './customer-feedback';
describe('Formularios de reseñas y preguntas',()=>{
 let http:HttpTestingController,logged:ReturnType<typeof signal<boolean>>;
 beforeEach(()=>{logged=signal(true);TestBed.configureTestingModule({imports:[CustomerFeedback],providers:[provideRouter([]),provideHttpClient(),provideHttpClientTesting(),{provide:AuthService,useValue:{isAuthenticated:logged,initialize:()=>Promise.resolve(null)}}]});http=TestBed.inject(HttpTestingController);});
 afterEach(()=>{http.verify();TestBed.resetTestingModule();});
 it('pide iniciar sesión y no envía mensajes como invitado',()=>{
  logged.set(false);const f=TestBed.createComponent(CustomerFeedback);f.componentRef.setInput('productId','product1');f.detectChanges();expect(f.nativeElement.textContent).toContain('Iniciar sesión');expect(f.nativeElement.querySelector('textarea')).toBeNull();
 });
 it('confirma moderación solo tras el guardado y bloquea envíos dobles',()=>{
  const f=TestBed.createComponent(CustomerFeedback);f.componentRef.setInput('productId','product1');f.detectChanges();const c:any=f.componentInstance;c.rating=5;c.text='Reseña de prueba para el producto';c.submit();c.submit();
  const r=http.expectOne('/api/v1/productos/product1/resenas');expect(r.request.body).toEqual({calificacion:5,comentario:c.text});expect(c.sent()).toBe(false);r.flush({estado:'PENDIENTE'});f.detectChanges();expect(f.nativeElement.textContent).toContain('pendiente de moderación');
 });
 it('conserva el texto si el servidor rechaza el envío',()=>{
  const f=TestBed.createComponent(CustomerFeedback);f.componentRef.setInput('productId','product1');f.detectChanges();const c:any=f.componentInstance;c.rating=5;c.text='Reseña que debe conservarse';c.submit();http.expectOne('/api/v1/productos/product1/resenas').flush({message:'Ya enviaste una reseña'},{status:409,statusText:'Conflict'});expect(c.sent()).toBe(false);expect(c.text).toContain('conservarse');expect(c.message()).toContain('Ya enviaste');
 });
 it('pinta las estrellas seleccionadas y envía esa calificación',()=>{
  const f=TestBed.createComponent(CustomerFeedback);f.componentRef.setInput('productId','product1');f.detectChanges();
  const radios=f.nativeElement.querySelectorAll('.rating-star input');radios[2].click();f.detectChanges();expect(f.nativeElement.querySelectorAll('.rating-star.is-filled')).toHaveLength(3);
  const c:any=f.componentInstance;c.text='Reseña con tres estrellas';c.submit();const r=http.expectOne('/api/v1/productos/product1/resenas');expect(r.request.body.calificacion).toBe(3);r.flush({estado:'PENDIENTE'});
 });
 it('empieza sin estrellas seleccionadas y exige elegir antes de enviar',()=>{
  const f=TestBed.createComponent(CustomerFeedback);f.componentRef.setInput('productId','product1');f.detectChanges();
  expect(f.nativeElement.querySelectorAll('.rating-star.is-filled')).toHaveLength(0);expect(f.nativeElement.querySelectorAll('.rating-star input:checked')).toHaveLength(0);
  const c:any=f.componentInstance;c.text='Una reseña sin calificación';c.submit();http.expectNone('/api/v1/productos/product1/resenas');expect(c.message()).toContain('Selecciona de 1 a 5');
 });
 it('previsualiza al pasar el mouse y conserva únicamente las estrellas elegidas al salir',()=>{
  const f=TestBed.createComponent(CustomerFeedback);f.componentRef.setInput('productId','product1');f.detectChanges();const stars=f.nativeElement.querySelectorAll('.rating-star');
  stars[3].dispatchEvent(new MouseEvent('mouseenter'));f.detectChanges();expect(f.nativeElement.querySelectorAll('.rating-star.is-filled')).toHaveLength(4);
  stars[1].querySelector('input').click();f.detectChanges();expect(f.nativeElement.querySelectorAll('.rating-star.is-filled')).toHaveLength(2);
  stars[4].dispatchEvent(new MouseEvent('mouseenter'));f.detectChanges();expect(f.nativeElement.querySelectorAll('.rating-star.is-filled')).toHaveLength(5);
  f.nativeElement.querySelector('.star-rating').dispatchEvent(new MouseEvent('mouseleave'));f.detectChanges();expect(f.nativeElement.querySelectorAll('.rating-star.is-filled')).toHaveLength(2);
 });
});
