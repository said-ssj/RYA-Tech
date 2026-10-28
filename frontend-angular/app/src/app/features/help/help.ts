import { CustomerFeedback } from '../../shared/customer-feedback/customer-feedback';
import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subject, Subscription, debounceTime } from 'rxjs';
import { HelpPage, HelpQuestion, HelpService, SupportContact } from './help.service';
import { LoadingState } from '../../shared/loading-state/loading-state';

const DEFAULT_CATEGORIES = [
  { id: 'compatibilidad', nombre: 'Compatibilidad y Asesorías' },
  { id: 'pagos', nombre: 'Pagos y Facturación' },
  { id: 'envios', nombre: 'Envíos y Entregas' },
  { id: 'garantias', nombre: 'Garantías y Postventa' },
  { id: 'armado', nombre: 'Ensamblaje y Configuración' },
];

const DEFAULT_QUESTIONS: HelpQuestion[] = [
  {
    id: '1',
    categoriaId: 'compatibilidad',
    categoria: 'Compatibilidad y Asesorías',
    pregunta: '¿Cómo sé si los componentes que elegí son 100% compatibles?',
    respuesta: 'En RYA Tech contamos con nuestro motor de Asesorías Inteligentes, el cual valida automáticamente la compatibilidad física y técnica entre procesadores (socket), placas madre (chipset y versión de BIOS), memorias RAM (DDR4/DDR5 y frecuencias), espacio en gabinete y consumo de potencia recomendado para la fuente de poder.'
  },
  {
    id: '2',
    categoriaId: 'compatibilidad',
    categoria: 'Compatibilidad y Asesorías',
    pregunta: '¿La placa madre vendrá con la BIOS actualizada para mi procesador?',
    respuesta: 'Sí. Si compras la placa madre junto con tu procesador o solicitas el servicio de ensamblaje, nuestros técnicos actualizan la BIOS a la versión más estable y compatible antes del despacho, sin costo adicional.'
  },
  {
    id: '3',
    categoriaId: 'compatibilidad',
    categoria: 'Compatibilidad y Asesorías',
    pregunta: '¿Qué hago si no sé qué componentes elegir para mi presupuesto?',
    respuesta: 'En nuestra sección de Asesorías puedes elegir tu perfil de uso (Gaming competitivo, Creación de contenido, Oficina o Inteligencia Artificial) e ingresar tu presupuesto aproximado. El sistema te recomendará configuraciones equilibradas sin cuellos de botella.'
  },
  {
    id: '4',
    categoriaId: 'pagos',
    categoria: 'Pagos y Facturación',
    pregunta: '¿Qué métodos de pago aceptan?',
    respuesta: 'Aceptamos transferencias bancarias directas (BCP, BBVA, Interbank), pagos instantáneos (Yape y Plin para montos autorizados) y tarjetas de crédito o débito (Visa, Mastercard, American Express).'
  },
  {
    id: '5',
    categoriaId: 'pagos',
    categoria: 'Pagos y Facturación',
    pregunta: '¿Los precios incluyen IGV? ¿Emiten Factura o Boleta?',
    respuesta: 'Sí, todos nuestros precios ya incluyen IGV. Al momento de finalizar tu compra en el checkout puedes seleccionar si deseas Boleta de Venta o Factura Electrónica ingresando tu RUC y Razón Social.'
  },
  {
    id: '6',
    categoriaId: 'pagos',
    categoria: 'Pagos y Facturación',
    pregunta: '¿Puedo pagar contra entrega?',
    respuesta: 'Por seguridad y gestión de inventario de componentes de alta gama, los pedidos se procesan previo pago verificado en nuestra plataforma o confirmación de transferencia.'
  },
  {
    id: '7',
    categoriaId: 'envios',
    categoria: 'Envíos y Entregas',
    pregunta: '¿Realizan envíos a todo el Perú?',
    respuesta: 'Sí, despachamos a nivel nacional a todas las provincias y departamentos mediante agencias autorizadas como Olva Courier y Shalom, con número de seguimiento para monitorear tu paquete en tiempo real.'
  },
  {
    id: '8',
    categoriaId: 'envios',
    categoria: 'Envíos y Entregas',
    pregunta: '¿Cuánto tarda en llegar mi pedido?',
    respuesta: 'En Lima Metropolitana las entregas se realizan entre 24 y 48 horas hábiles. Para envíos a provincia, el tiempo promedio suele ser de 2 a 4 días hábiles, dependiendo de la agencia y la ciudad de destino.'
  },
  {
    id: '9',
    categoriaId: 'envios',
    categoria: 'Envíos y Entregas',
    pregunta: '¿Puedo recoger mi pedido de forma presencial?',
    respuesta: 'Sí, puedes seleccionar la opción de recojo en nuestro almacén principal en Lima. Te notificaremos por correo y WhatsApp en cuanto tu pedido esté embalado y listo para retiro.'
  },
  {
    id: '10',
    categoriaId: 'garantias',
    categoria: 'Garantías y Postventa',
    pregunta: '¿Los productos cuentan con garantía oficial?',
    respuesta: 'Todos nuestros productos son 100% originales, nuevos, sellados de fábrica y cuentan con garantía local oficial de la marca (desde 12 hasta 36 meses dependiendo del fabricante y componente).'
  },
  {
    id: '11',
    categoriaId: 'garantias',
    categoria: 'Garantías y Postventa',
    pregunta: '¿Cómo solicito garantía por falla de fábrica?',
    respuesta: 'Puedes iniciar una solicitud desde Mis Pedidos o escribiéndonos a soporte con tu número de pedido y serie del producto para gestionar el proceso de RMA inmediatamente.'
  },
  {
    id: '12',
    categoriaId: 'garantias',
    categoria: 'Garantías y Postventa',
    pregunta: '¿Aceptan cambios o devoluciones?',
    respuesta: 'Sí, aceptamos solicitudes de cambio o devolución dentro de los primeros 7 días calendario tras recibir tu pedido, siempre que conserve empaques originales, sellos, accesorios y manuales intactos.'
  },
  {
    id: '13',
    categoriaId: 'armado',
    categoria: 'Ensamblaje y Configuración',
    pregunta: '¿Puedo pedir mi PC armada y lista para usar?',
    respuesta: '¡Sí! Brindamos servicio de ensamblaje profesional con gestión de cables (cable management), configuración de perfiles XMP/EXPO de memoria RAM y pruebas de estrés térmico antes de la entrega.'
  }
];

@Component({
  selector: 'app-help',
  imports: [CustomerFeedback, RouterLink, LoadingState],
  templateUrl: './help.html',
  styleUrl: './help.scss',
})
export class Help implements OnInit {
  private readonly service = inject(HelpService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly searchInput = new Subject<string>();
  private request?: Subscription;
  protected readonly query = signal('');
  protected readonly searchTerm = signal('');
  protected readonly category = signal('');
  protected readonly currentPage = signal(0);
  protected readonly data = signal<HelpPage | null>(null);
  protected readonly contact = signal<SupportContact | null>(null);
  protected readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  protected readonly contactState = signal<'loading' | 'ready' | 'error'>('loading');
  protected readonly fallback = signal(false);

  ngOnInit(): void {
    this.searchInput.pipe(debounceTime(300), takeUntilDestroyed(this.destroyRef))
      .subscribe(value => this.navigate(value.trim(), this.category(), 0, true));
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(params => {
      const q = (params.get('q') ?? '').slice(0, 200);
      this.query.set(q);
      this.searchTerm.set(q);
      this.category.set(params.get('categoria') ?? '');
      const page = Number(params.get('pagina') ?? 1);
      this.currentPage.set(Number.isSafeInteger(page) && page > 0 && page < 1000000 ? page - 1 : 0);
      this.load();
    });
    this.loadContact();
  }

  protected search(value: string): void {
    this.searchTerm.set(value);
    this.searchInput.next(value);
  }

  protected clearSearch(): void {
    this.searchInput.next('');
    this.searchTerm.set('');
    this.navigate('', this.category());
  }

  protected clear(): void {
    this.searchInput.next('');
    this.searchTerm.set('');
    this.navigate('', '');
  }

  protected chooseCategory(id: string): void {
    this.navigate(this.query(), id);
  }

  protected goTo(page: number): void {
    if (page >= 0 && page < (this.data()?.totalPages ?? 0)) {
      this.navigate(this.query(), this.category(), page);
    }
  }

  protected load(): void {
    this.request?.unsubscribe();
    this.state.set('loading');
    this.fallback.set(false);
    this.request = this.service.questions(this.query(), this.category(), this.currentPage())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: data => {
          if (data && data.content && data.content.length > 0) {
            if (data.totalPages > 0 && this.currentPage() >= data.totalPages) {
              this.navigate(this.query(), this.category(), data.totalPages - 1);
              return;
            }
            this.data.set(data);
          } else {
            this.data.set(this.buildFallbackPage());
          }
          this.state.set('ready');
        },
        error: () => {
          this.fallback.set(true);
          this.data.set(this.buildFallbackPage());
          this.state.set('ready');
        },
      });
  }

  protected loadContact(): void {
    this.contactState.set('loading');
    this.service.contact().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: contact => {
        this.contact.set(contact);
        this.contactState.set('ready');
      },
      error: () => this.contactState.set('error'),
    });
  }

  protected getWhatsappLink(message: string): string | null {
    const base = this.contact()?.whatsappUrl;
    if (!base) return null;
    const separator = base.includes('?') ? '&' : '?';
    return `${base}${separator}text=${encodeURIComponent(message)}`;
  }

  protected getEmailLink(subject: string): string | null {
    const email = this.contact()?.email;
    if (!email) return null;
    return `mailto:${email}?subject=${encodeURIComponent(subject)}`;
  }

  private buildFallbackPage(): HelpPage {
    const q = this.query().toLowerCase().trim();
    const cat = this.category();
    const filtered = DEFAULT_QUESTIONS.filter(item => {
      const matchCat = !cat || item.categoriaId === cat;
      const matchQuery = !q || item.pregunta.toLowerCase().includes(q) || item.respuesta.toLowerCase().includes(q);
      return matchCat && matchQuery;
    });
    return {
      content: filtered,
      categorias: DEFAULT_CATEGORIES,
      page: 0,
      size: 20,
      totalElements: filtered.length,
      totalPages: 1,
    };
  }

  private navigate(q: string, category: string, page = 0, replaceUrl = false): void {
    const queryParams: Record<string, string | number> = {};
    if (q) queryParams['q'] = q;
    if (category) queryParams['categoria'] = category;
    if (page) queryParams['pagina'] = page + 1;
    void this.router.navigate([], { relativeTo: this.route, queryParams, replaceUrl });
  }
}
