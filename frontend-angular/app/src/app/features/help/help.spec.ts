import {provideHttpClient} from '@angular/common/http';
import {AuthService} from '../../core/auth/auth.service';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Help } from './help';
import { HelpPage, HelpService } from './help.service';

describe('Centro de ayuda', () => {
  const params = new BehaviorSubject(convertToParamMap({}));
  const api = { questions:vi.fn(),contact:vi.fn() };
  const response: HelpPage = { content:[{id:'faq-1',pregunta:'¿Cómo veo mis compras?',respuesta:'Abre Mis compras.\nInicia sesión.',categoriaId:null,categoria:'General'}], categorias:[], page:0,size:12,totalElements:1,totalPages:1 };
  beforeEach(() => {
    params.next(convertToParamMap({}));
    api.questions.mockReset().mockReturnValue(of(response));
    api.contact.mockReset().mockReturnValue(of({email:null,telefono:null,telefonoUrl:null,whatsappUrl:null,horario:null}));
    TestBed.configureTestingModule({imports:[Help],providers:[provideHttpClient(), {provide:AuthService,useValue:{isAuthenticated:()=>false,initialize:()=>Promise.resolve(null)}},provideRouter([]),
      {provide:ActivatedRoute,useValue:{queryParamMap:params}}, {provide:HelpService,useValue:api},
    ]});
  });

  it('muestra preguntas en acordeones nativos accesibles con texto seguro', () => {
    api.questions.mockReturnValue(of({...response,content:[{...response.content[0],respuesta:'<script>no ejecutar</script>\nRespuesta'}]}));
    const fixture=TestBed.createComponent(Help); fixture.detectChanges();
    const element=fixture.nativeElement as HTMLElement;
    expect(element.querySelector('details summary')?.textContent).toContain('¿Cómo veo mis compras?');
    expect(element.querySelector('details')?.open).toBe(false);
    expect(element.querySelector('.faq-answer script')).toBeNull();
    expect(element.querySelector('.faq-answer p')?.textContent).toContain('<script>no ejecutar</script>');
  });

  it('recupera búsqueda, categoría y página desde la URL', () => {
    params.next(convertToParamMap({q:'envio',categoria:'categoria-1',pagina:'2'}));
    api.questions.mockReturnValue(of({...response,totalPages:2}));
    const fixture=TestBed.createComponent(Help); fixture.detectChanges();
    expect(api.questions).toHaveBeenCalledWith('envio','categoria-1',1);
    expect((fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input[type=search]')?.value).toBe('envio');
  });

  it('busca al escribir y no restaura una búsqueda pendiente después de limpiarla', async () => {
    const navigate=vi.spyOn(TestBed.inject(Router),'navigate').mockResolvedValue(true);
    const fixture=TestBed.createComponent(Help); fixture.detectChanges();
    const input=(fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input[type=search]')!;
    input.value='envio'; input.dispatchEvent(new Event('input')); fixture.detectChanges();
    await new Promise(resolve=>setTimeout(resolve,340));
    expect(navigate).toHaveBeenLastCalledWith([],expect.objectContaining({queryParams:{q:'envio'},replaceUrl:true}));
    input.value='pendiente'; input.dispatchEvent(new Event('input')); fixture.detectChanges();
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('[aria-label="Limpiar búsqueda"]')!.click();
    await new Promise(resolve=>setTimeout(resolve,340));
    expect(navigate).toHaveBeenLastCalledWith([],expect.objectContaining({queryParams:{}}));
  });

  it('mantiene las preguntas editoriales y avisa del fallo de red sin perder el contacto', () => {
    api.questions.mockReturnValue(of({...response,content:[],totalElements:0,totalPages:0}));
    api.contact.mockReturnValue(of({email:'soporte@example.test',telefono:null,telefonoUrl:null,whatsappUrl:null,horario:'Horario de prueba'}));
    const fixture=TestBed.createComponent(Help); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('details summary')).toBeTruthy();
    api.questions.mockReturnValue(throwError(()=>new Error('offline')));
    params.next(convertToParamMap({q:'envio'})); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('No pudimos cargar las preguntas');
    expect((fixture.nativeElement as HTMLElement).querySelector('a[href^="mailto:soporte@example.test"]')).toBeTruthy();
  });

  it('no muestra enlaces ficticios cuando los canales no están configurados', () => {
    const fixture=TestBed.createComponent(Help); fixture.detectChanges();
    const element=fixture.nativeElement as HTMLElement;
    expect(element.querySelector('.contact-pending')?.textContent).toContain('todavía no se han publicado');
    expect(element.querySelector('a[href^="mailto:"]')).toBeNull();
    expect(element.querySelector('a[href^="tel:"]')).toBeNull();
  });

  it('muestra el teléfono provisional y el horario sin ofrecer una llamada', () => {
    api.contact.mockReturnValue(of({email:null,telefono:'000 000 000',telefonoUrl:null,whatsappUrl:null,
      horario:'Lunes a viernes: 9:00 a. m. a 10:00 p. m. | Sábados: 9:00 a. m. a 12:00 p. m. | Domingos: cerrado.'}));
    const fixture=TestBed.createComponent(Help); fixture.detectChanges();
    const element=fixture.nativeElement as HTMLElement;
    expect(element.querySelector('.provisional-phone')?.textContent).toContain('000 000 000');
    expect(element.querySelector('.contact-hours')?.textContent).toContain('Domingos: cerrado');
    expect(element.querySelector('a[href^="tel:"]')).toBeNull();
  });
});
