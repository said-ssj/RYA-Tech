import {TestBed} from '@angular/core/testing';
import {provideRouter} from '@angular/router';
import {registerLocaleData} from '@angular/common';
import locale from '@angular/common/locales/es-PE';
import {of,Subject,throwError} from 'rxjs';
import {beforeEach,afterEach,describe,it,expect,vi} from 'vitest';
import {Advisor} from './advisor';
import {AdvisorService} from './advisor.service';
import {AuthService} from '../../core/auth/auth.service';
registerLocaleData(locale);
describe('Selección desplegable de componentes',()=>{
 let fixture:any,component:Advisor,api:any;
 const cpu={id:'cpu1',componentTypeCode:'CPU',name:'Procesador de prueba',brand:'AMD',price:500,currency:'PEN',stock:30,imageUrl:null,summary:'CPU-1'};
 const boot={profileFamilies:[],componentTypes:[{code:'CPU',name:'Procesadores'},{code:'BOARD',name:'Placas Madre'}],resolutions:[]};
 beforeEach(async()=>{
  localStorage.removeItem('rya.builder.ui.v1');
  api={getBootstrap:vi.fn(()=>of(boot)),getCompatibleOptions:vi.fn(()=>of([cpu])),validateSelection:vi.fn(()=>of({components:[cpu],total:500,compatible:true,warnings:[]}))};
  TestBed.configureTestingModule({imports:[Advisor],providers:[provideRouter([]),{provide:AdvisorService,useValue:api},{provide:AuthService,useValue:{initialize:()=>Promise.resolve(null),isAuthenticated:()=>false}}]});
  fixture=TestBed.createComponent(Advisor);component=fixture.componentInstance;component.selectMode('manual');fixture.detectChanges();await fixture.whenStable();
 });
 afterEach(()=>{TestBed.resetTestingModule();localStorage.removeItem('rya.builder.ui.v1');});
 const button=(code:string)=>fixture.nativeElement.querySelector('#component-trigger-'+code) as HTMLButtonElement;
 it('abre las opciones debajo de su encabezado y las contrae sin borrar la selección',()=>{
  button('CPU').click();fixture.detectChanges();
  expect(button('CPU').getAttribute('aria-expanded')).toBe('true');
  const panel=fixture.nativeElement.querySelector('#component-panel-CPU');expect(button('CPU').nextElementSibling).toBe(panel);expect(panel.textContent).toContain(cpu.name);
  panel.querySelector('.component-card-actions button').click();fixture.detectChanges();
  button('CPU').click();fixture.detectChanges();expect(fixture.nativeElement.querySelector('[role=region]')).toBeNull();expect(component.selectedFor('CPU')?.id).toBe('cpu1');expect(button('CPU').textContent).toContain(cpu.name);
 });
 it('abre únicamente el componente actual y descarta respuestas anteriores',()=>{
  const old=new Subject<any[]>();api.getCompatibleOptions.mockReturnValueOnce(old).mockReturnValueOnce(of([]));
  button('CPU').click();button('BOARD').click();old.next([cpu]);fixture.detectChanges();
  expect(fixture.nativeElement.querySelector('#component-panel-CPU')).toBeNull();expect(button('BOARD').getAttribute('aria-expanded')).toBe('true');expect(component.options()).toEqual([]);
  expect(fixture.nativeElement.querySelector('#component-panel-BOARD').textContent).toContain('No hay productos');
 });
 it('permite reintentar en el panel que falló sin cerrarlo',()=>{
  api.getCompatibleOptions.mockReturnValueOnce(throwError(()=>new Error('offline'))).mockReturnValueOnce(of([cpu]));
  button('CPU').click();fixture.detectChanges();expect(component.optionsStatus()).toBe('error');
  component.selectComponentType('CPU');fixture.detectChanges();expect(button('CPU').getAttribute('aria-expanded')).toBe('true');expect(component.optionsStatus()).toBe('ready');
 });
 it('usa iconos específicos, botones de aplicar uniformes y no muestra etiquetas eliminadas',()=>{
  button('CPU').click();fixture.detectChanges();
  expect(button('BOARD').textContent).toContain('developer_board');expect(component.componentIcon('MOUSE')).toBe('mouse');expect(component.componentIcon('AUD')).toBe('headphones');
  expect(fixture.nativeElement.querySelectorAll('.component-card-actions .apply-component.button-primary').length).toBe(1);
  expect(fixture.nativeElement.textContent).not.toContain('Sin incompatibilidades');expect(fixture.nativeElement.textContent).not.toContain('En stock');
 });
 it('muestra el estado de búsqueda real',()=>{
  const pending=new Subject<any[]>();api.getCompatibleOptions.mockReturnValue(pending);button('CPU').click();expect(component.engineStatus()).toBe('Cargando...');
  pending.next([cpu]);expect(component.engineStatus()).toBe('Piezas encontradas');component.toggleComponentType('CPU');expect(component.engineStatus()).toBe('Motor listo');
 });
 it('separa los periféricos de la torre y conserva sus selecciones al ocultarlos',()=>{
  const extras=[{code:'TECLADOS',name:'Teclados'},{code:'MOUSE',name:'Mouse'},{code:'MONITORES',name:'Monitores'},{code:'MICROFONOS',name:'Micrófonos'},{code:'CAMARAS-WEB',name:'Cámaras Web'},{code:'MOUSEPADS',name:'Mousepads'},{code:'AUD',name:'Audifonos'}];
  component.bootstrap.set({...boot,componentTypes:[...boot.componentTypes,...extras]});fixture.detectChanges();
  expect(component.mainComponentTypes().map(t=>t.code)).toEqual(['CPU','BOARD']);expect(component.peripheralTypes()).toHaveLength(7);expect(button('MOUSE')).toBeNull();
  fixture.nativeElement.querySelector('.peripherals-toggle').click();fixture.detectChanges();
  expect(button('MOUSE').closest('#peripheral-components')).not.toBeNull();
  const mouse={...cpu,id:'mouse1',componentTypeCode:'MOUSE',name:'Mouse de prueba'};api.getCompatibleOptions.mockReturnValue(of([mouse]));
  button('MOUSE').click();fixture.detectChanges();fixture.nativeElement.querySelector('#component-panel-MOUSE .apply-component').click();fixture.detectChanges();
  fixture.nativeElement.querySelector('.peripherals-toggle').click();fixture.detectChanges();expect(button('MOUSE')).toBeNull();expect(component.selectedFor('MOUSE')?.id).toBe('mouse1');
  fixture.nativeElement.querySelector('.peripherals-toggle').click();fixture.detectChanges();expect(button('MOUSE').textContent).toContain('Mouse de prueba');
 });
});
