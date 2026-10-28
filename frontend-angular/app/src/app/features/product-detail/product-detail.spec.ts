import {provideHttpClient} from '@angular/common/http';
import {AuthService} from '../../core/auth/auth.service';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, Subject, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ProductDetailComponent } from './product-detail';
import { ProductDetailService } from './product-detail.service';
import { CatalogService } from '../catalog/catalog.service';
import { CartService } from '../../core/cart/cart.service';
import { FavoritesService } from '../../core/favorites/favorites.service';
import { registerLocaleData } from '@angular/common';
import localeEsPe from '@angular/common/locales/es-PE';

registerLocaleData(localeEsPe);

describe('Recuperación del detalle de producto', () => {
  const params = new BehaviorSubject(convertToParamMap({ slug: 'cpu' }));
  const getBySlug = vi.fn();
  beforeEach(() => {
    params.next(convertToParamMap({ slug: 'cpu' }));
    getBySlug.mockReset().mockReturnValue(throwError(() => ({ status: 503 })));
    TestBed.configureTestingModule({ imports: [ProductDetailComponent], providers: [provideHttpClient(), {provide:AuthService,useValue:{isAuthenticated:()=>false,initialize:()=>Promise.resolve(null)}},provideRouter([]),
      { provide: ActivatedRoute, useValue: { paramMap: params } },
      { provide: ProductDetailService, useValue: { getBySlug } },
      { provide: CatalogService, useValue: {} }, { provide: CartService, useValue: {} },
      { provide: FavoritesService, useValue: { has: () => false } },
    ] });
  });
  it('distingue una caída temporal de un producto inexistente y permite reintentar', () => {
    const fixture = TestBed.createComponent(ProductDetailComponent); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('No pudimos cargar el producto');
    getBySlug.mockReturnValue(throwError(() => ({ status: 404 })));
    fixture.nativeElement.querySelector('app-request-error button').click(); fixture.detectChanges();
    expect(getBySlug).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.textContent).toContain('No encontramos este producto');
  });
  it('sigue atendiendo cambios de ruta después de un error y muestra skeletons', () => {
    const fixture = TestBed.createComponent(ProductDetailComponent); fixture.detectChanges();
    getBySlug.mockReturnValue(new Subject()); params.next(convertToParamMap({ slug: 'otro' })); fixture.detectChanges();
    expect(getBySlug).toHaveBeenLastCalledWith('otro');
    expect(fixture.nativeElement.querySelector('app-loading-state [role=status]')).toBeTruthy();
  });
  it('renders one customer-facing value per feature and a separate safe source link',()=>{
    const fixture=TestBed.createComponent(ProductDetailComponent);fixture.detectChanges();const component=fixture.componentInstance as any;
    const attr=(code:string,name:string,value:string,unit:string|null=null)=>({code,name,value,unit,dataType:'TEXTO'});
    component.product.set({id:'cpu',sku:'CPU-1',name:'Ryzen 5',slug:'ryzen-5',category:'Procesadores',categorySlug:'procesadores',price:100,availableStock:0,shippable:true,images:[],attributes:[
      attr('socket_cpu','Socket','AM4'),attr('CATALOG_TECH_CPU_SOCKET','Ficha · Socket','AM4'),
      attr('cache_l3_kb','Caché L3 Total','8192','KB'),attr('CATALOG_TECH_CPU_L3_MB','Ficha · Caché L3','8','MB'),
      attr('CATALOG_TECH_TYPE','Ficha · Familia técnica','cpu'),attr('CATALOG_TECH_STATUS','Ficha · Estado de revisión técnica','Pendiente de revisión; no certifica compatibilidad'),
      attr('CATALOG_TECH_CPU_POWER_NOTES','Ficha · Criterio de potencia','Dato heredado potencia_base_w'),
      attr('CATALOG_TECH_SOURCE','Ficha · Fuente de especificaciones','https://example.com/cpu')
    ]});component.state.set('ready');fixture.detectChanges();
    const section=fixture.nativeElement.querySelector('.specifications');expect(section.querySelectorAll('dl > div')).toHaveLength(2);
    expect(section.textContent).toContain('8 MB');expect(section.textContent).not.toContain('8192');expect(section.textContent).not.toContain('Dato heredado');
    expect(section.textContent).not.toContain('Familia técnica');expect(section.textContent).not.toContain('Ficha ·');
    expect(section.querySelector('.spec-pending').getAttribute('aria-label')).toBe('Pendiente de revisión');
    expect(section.querySelector('.spec-source a').getAttribute('href')).toBe('https://example.com/cpu');
    expect(component.product().attributes).toHaveLength(8);
  });
  it('does not send peripheral buyers to the component configurator and keeps useful limitations',()=>{
    const fixture=TestBed.createComponent(ProductDetailComponent);fixture.detectChanges();const component=fixture.componentInstance as any;
    component.product.set({id:'mouse',sku:'MOUSE',name:'Mouse',category:'Mouse',categorySlug:'mouse',slug:'mouse',price:100,availableStock:0,shippable:true,images:[],attributes:[
      {code:'CATALOG_TECH_TYPE',name:'Tipo',value:'mouse'},
      {code:'CATALOG_TECH_MOUSE_CONNECTIONS',name:'Conexiones',value:'Bluetooth; USB-C para carga'},
      {code:'CATALOG_TECH_MOUSE_INTERNAL_NOTES',name:'Notas internas',value:'No confundir modelos'}
    ]});component.state.set('ready');fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Bluetooth; USB-C para carga');expect(fixture.nativeElement.textContent).not.toContain('No confundir modelos');
    expect(fixture.nativeElement.querySelector('.spec-review-note').textContent).not.toContain('asesor');expect(fixture.nativeElement.querySelector('.compatibility-card').textContent).not.toContain('configurador');
  });
});
