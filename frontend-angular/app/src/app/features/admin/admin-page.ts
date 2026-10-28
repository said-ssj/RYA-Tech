import {PricingAdmin} from './pricing-admin';
import { Component, DestroyRef, HostListener, OnInit, ViewChild, ViewEncapsulation, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, NgForm } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { Observable, Subject, Subscription, debounceTime, finalize } from 'rxjs';
import { ADMIN_MODULES } from './admin-modules';
import { AdminPagedResult, AdminRow, AdminService, AdminStats, AlmacenItem, InventarioItem, MovimientoInventario, AjusteStockPayload } from './admin.service';
import { TechnicalEditor } from './technical-editor';
import { ProductMedia } from './product-media';
import { PreferencesService } from '../../core/preferences/preferences.service';
import { NotificationService } from '../../core/notifications/notification.service';
import { DialogFocus } from '../../shared/dialog-focus/dialog-focus';
import { TechnicalEntry } from './technical-entry';
import { AuthService } from '../../core/auth/auth.service';
import { ProductImport } from './product-import';
import { CatalogReferences } from './catalog-references';
import { ProductTechnicalForm } from './product-technical-form';
import { ProductTechnicalFicha } from './product-technical.models';
import { AdminReferenceItem } from './admin.service';
import { TechnicalFieldManager } from './technical-field-manager';
import { OperationsAdmin } from './operations-admin';
import { ShippingAdmin } from './shipping-admin';
import { SidebarResize } from './sidebar-resize';

@Component({
  selector: 'app-admin-page',
  encapsulation: ViewEncapsulation.None,
  imports: [PricingAdmin, CommonModule, FormsModule, RouterLink, RouterLinkActive, TechnicalEditor, ProductMedia, DialogFocus, ProductImport, CatalogReferences, ProductTechnicalForm, SidebarResize, ShippingAdmin, OperationsAdmin, TechnicalFieldManager],
  styleUrls: ['./admin.scss', './sidebar-resize.scss', './admin-filter.scss'],
  templateUrl: './admin-page.html',
})
export class AdminPage implements OnInit {
  @ViewChild(PricingAdmin) pricingAdmin?: PricingAdmin;
  @ViewChild(TechnicalFieldManager) technicalFieldManager?: TechnicalFieldManager;
  @ViewChild(CatalogReferences) referencesEditor?: CatalogReferences;
  protected readonly customFieldsBusy = signal(false);
  protected customSchemaChanged(event:{categoryId:string;schema:import('./product-technical.models').TechnicalSchema|null}){
    this.categories.update(rows=>rows.map(row=>row.id===event.categoryId?{...row,fichaEsquema:event.schema}:row));
  }
  @ViewChild(OperationsAdmin) operationsAdmin?: OperationsAdmin;
  @ViewChild(ShippingAdmin) shippingAdmin?: ShippingAdmin;
  @ViewChild(ProductTechnicalForm) technicalForm?: ProductTechnicalForm;
  protected technicalSchema(){return this.categories().find(category=>category.id===this.formData().categoriaId)?.fichaEsquema??null;}
  protected setTechnical(value:ProductTechnicalFicha){this.formData.update(data=>({...data,fichaTecnica:value}));}
  protected readonly importBusy = signal(false);
  @ViewChild(ProductMedia) productMediaComponent?: ProductMedia;
  @ViewChild(ProductImport) productImporter?: ProductImport;
  private dockTimer?: ReturnType<typeof setTimeout>;
  private dockFocus = '';
  @ViewChild(TechnicalEditor) technicalEditor?: TechnicalEditor;
  private readonly technicalEntry = inject(TechnicalEntry);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly adminService = inject(AdminService);
  private readonly notifications = inject(NotificationService);
  private readonly preferences = inject(PreferencesService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly searchSubject = new Subject<{value:string; section:string}>();
  private listSub?: Subscription;
  protected readonly metadataLoading = signal(false);
  protected readonly metadataError = signal<string | null>(null);
  protected readonly statsLoading = signal(false);
  protected readonly statsUnavailable = signal(false);
  protected readonly connected = () => this.adminService.isModuleConnected(this.section);
  private formBaseline = '';
  private galleryBaseline = '[]';
  private slugEdited = false;
  protected readonly savingCategory = signal(false);
  protected readonly savingBrand = signal(false);
  protected readonly categoryError = signal<string | null>(null);
  protected readonly brandError = signal<string | null>(null);
  protected readonly savingReference = () => this.savingCategory() || this.savingBrand();
  protected readonly pendingRows = signal<ReadonlySet<string>>(new Set());

  readonly modules = ADMIN_MODULES;

  private readonly params = toSignal(this.route.paramMap, { initialValue: this.route.snapshot.paramMap });

  get design() { return this.route.snapshot.data['design'] === true; }
  get base() { return this.design ? '/admin/diseno' : '/admin'; }
  get section() { return this.params().get('section') ?? ''; }
  get current() { return this.modules.find(module => module.id === this.section); }

  // Resumen stats
  protected readonly stats = signal<AdminStats>({
    totalProductos: null,
    pedidosPendientes: null,
    stockBajo: null,
    fichasPorVerificar: null,
    categoriasActivas: null,
  });

  // Estado del listado
  protected readonly items = signal<AdminRow[]>([]);
  protected readonly totalItems = signal(0);
  protected readonly totalPages = signal(1);
  protected readonly currentPage = signal(0);
  protected readonly pageSize = signal(10);
  protected readonly searchTerm = signal('');
  protected readonly statusFilter = signal('');
  protected readonly categoriaFilter = signal('');
  protected readonly filterMenuOpen = signal(false);
  protected readonly isLoading = signal(false);
  protected readonly serverError = signal<string | null>(null);

  // Metadata para selectores dinámicos
  protected readonly categories = signal<AdminReferenceItem[]>([]);
  protected readonly brands = signal<Array<{ id: string; nombre: string; slug: string }>>([]);

  // Estado para creación rápida de Categorías y Marcas
  protected readonly isCreatingCategory = signal(false);
  protected readonly newCategoryName = signal('');
  protected readonly newCategoryAcronym = signal('');
  protected readonly newCategoryParentId = signal('');

  protected readonly isCreatingBrand = signal(false);
  protected readonly newBrandName = signal('');
  protected readonly newBrandLogoUrl = signal('');

  // Estado para gestión de inventario
  protected readonly almacenes = signal<AlmacenItem[]>([]);
  protected readonly showStockModal = signal(false);
  protected readonly selectedStockRow = signal<AdminRow | null>(null);
  protected readonly isSavingStock = signal(false);
  protected readonly stockError = signal<string | null>(null);
  protected readonly stockCurrent = signal<InventarioItem | null>(null);
  protected readonly stockLoading = signal(false);
  protected readonly stockProducts = signal<AdminRow[]>([]);
  protected readonly stockProductsLoading = signal(false);
  protected readonly stockProductsError = signal<string | null>(null);
  protected readonly stockProductQuery = signal('');
  protected readonly stockProductPage = signal(0);
  protected readonly stockProductPages = signal(0);
  protected readonly almacenesError = signal<string | null>(null);
  protected readonly kardexPage = signal(0);
  protected readonly kardexPages = signal(0);
  private stockLookupSub?: Subscription;
  private stockProductsSub?: Subscription;
  private kardexSub?: Subscription;
  private stockOperation = '';
  private stockAttempt: AjusteStockPayload | null = null;
  protected readonly stockUncertain = signal(false);
  protected readonly stockForm = signal<{
    productoId: string;
    almacenId: string;
    tipo: 'ENTRADA' | 'SALIDA' | 'AJUSTE';
    cantidad: number | null;
    nota: string;
  }>({
    productoId: '',
    almacenId: '',
    tipo: 'ENTRADA',
    cantidad: null,
    nota: '',
  });

  // Estado para Kardex
  protected readonly showKardexModal = signal(false);
  protected readonly kardexLoading = signal(false);
  protected readonly kardexError = signal<string | null>(null);
  protected readonly kardexItems = signal<MovimientoInventario[]>([]);
  protected readonly selectedKardexRow = signal<AdminRow | null>(null);

  // Estado para gestión de Asesorías
  protected readonly showAsesoriaModal = signal(false);
  protected readonly isEditingAsesoria = signal(false);
  protected readonly isSavingAsesoria = signal(false);
  protected readonly asesoriaFormError = signal<string | null>(null);
  protected readonly familiasAsesoria = signal<any[]>([]);
  protected readonly isCreatingFamilia = signal(false);
  protected readonly newFamiliaCodigo = signal('');
  protected readonly newFamiliaNombre = signal('');
  protected readonly newFamiliaIcono = signal('tune');
  protected readonly savingFamilia = signal(false);
  protected readonly familiaError = signal<string | null>(null);
  protected readonly asesoriaFormData = signal<{
    id?: string;
    familiaId: string;
    codigo: string;
    nombre: string;
    descripcion: string;
    prioridadCpu: number;
    prioridadGpu: number;
    ramMinimaGb: number;
    requiereGpuDedicada: boolean;
    presupuestoSugeridoMin: number | null;
    presupuestoSugeridoMax: number | null;
    activo: boolean;
    reglasPresupuesto: Array<{
      id?: string;
      tipoComponenteCodigo: string;
      prioridad: number;
      porcentajeMin?: number;
      porcentajeMax?: number;
      obligatorio: boolean;
    }>;
    necesidades: Array<{
      id?: string;
      codigo?: string;
      nombre: string;
      descripcion?: string;
    }>;
  }>({
    familiaId: '',
    codigo: '',
    nombre: '',
    descripcion: '',
    prioridadCpu: 5,
    prioridadGpu: 5,
    ramMinimaGb: 16,
    requiereGpuDedicada: false,
    presupuestoSugeridoMin: 2500,
    presupuestoSugeridoMax: 6000,
    activo: true,
    reglasPresupuesto: [],
    necesidades: [],
  });

  // Estado del modal de formulario
  protected readonly showFormModal = signal(false);
  protected readonly isEditing = signal(false);
  protected readonly isSaving = signal(false);
  protected readonly isLoadingDetail = signal(false);
  protected readonly detailLoadError = signal<string | null>(null);
  private currentEditRowId: string | null = null;
  private detailSub?: Subscription;
  protected readonly formError = signal<string | null>(null);
  protected readonly formData = signal<{
    id?: string;
    nombre: string;
    sku: string;
    slug?: string;
    marca: string;
    marcaId?: string;
    categoria: string;
    categoriaId?: string;
    precio: number | null;
    costoReferencia?: number | null;
    stockInicial: number | null;
    estado: string;
    descripcion: string;
    imagenPrincipalUrl?: string;
    imagenes?: any[];
    requiereSerie?: boolean;
    requiereEnvio?: boolean;
    tipo?: string;
    fichaTecnica?: ProductTechnicalFicha|null;
  }>({
    nombre: '',
    sku: '',
    slug: '',
    marca: '',
    marcaId: '',
    categoria: '',
    categoriaId: '',
    precio: null,
    costoReferencia: null,
    stockInicial: 0,
    estado: 'active',
    descripcion: '',
    imagenPrincipalUrl: '',
    imagenes: [],
  });

  ngOnInit(): void {
    const leave = () => this.canLeave();
    this.technicalEntry.sectionLeave = leave;
    this.destroyRef.onDestroy(() => { if(this.technicalEntry.sectionLeave === leave) this.technicalEntry.sectionLeave = undefined; });
    this.loadStats();
    this.loadMetadata();

    // Debounce para búsqueda reactiva
    this.searchSubject.pipe(
      debounceTime(300),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe(query => {
      if (query.section !== this.section) return;
      this.searchTerm.set(query.value);
      this.currentPage.set(0);
      this.loadItems();
    });

    // Carga de items al cambiar de sección
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.currentPage.set(0);
      this.searchTerm.set('');
      this.statusFilter.set('');
      this.categoriaFilter.set('');
      this.filterMenuOpen.set(false);
      this.loadItems();
    });
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(params => {
      clearTimeout(this.dockTimer);
      this.dockTimer = setTimeout(() => {
        const search = params.get('buscar');
        if (search && this.section === 'inventario') { this.searchTerm.set(search); this.currentPage.set(0); this.loadItems(); }
        const action = params.get('accion');
        if (this.section === 'inventario' && action === 'stock') { if (this.canLeave()) this.openStockModal(); return; }
        if (this.section !== 'catalogo' || !action) return;
        if (!this.canLeave()) return;
        if (action === 'nuevo') { this.openCreateForm(); return; }
        if (action === 'importar' && !this.design) { this.productImporter?.open.set(true); return; }
        const id = params.get('producto');
        if (!id || !['editar', 'imagenes', 'ficha'].includes(action)) return;
        this.dockFocus = action;
        this.openEditForm({id, code:'', title:'Consultar producto', status:'draft', statusText:'', rawData:{}});
      });
    });
    this.destroyRef.onDestroy(() => clearTimeout(this.dockTimer));
  }

  protected theme(): void {
    this.preferences.setTheme(this.preferences.theme() === 'light' ? 'dark' : 'light');
  }

  protected loadStats(): void {
    this.statsLoading.set(true);
    (this.design ? this.adminService.getPreviewStats() : this.adminService.getStats()).pipe(takeUntilDestroyed(this.destroyRef)).subscribe(stats => {
      this.stats.set(stats);
      this.statsLoading.set(false);
      this.statsUnavailable.set(!this.design && Object.values(stats).some(value => value === null));
    });
  }

  protected loadMetadata(): void {
    if (this.design) return;
    this.metadataLoading.set(true); this.metadataError.set(null);
    this.adminService.getMetadata().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: meta => {
        this.categories.set(meta.categorias); this.brands.set(meta.marcas); this.metadataLoading.set(false);
        if (this.showFormModal() && this.isEditing()) this.formData.update(data => ({...data,
          categoriaId:data.categoriaId||meta.categorias.find(c=>c.nombre===data.categoria)?.id||'',
          marcaId:data.marcaId||meta.marcas.find(m=>m.nombre===data.marca)?.id||''}));
      },
      error: err => { this.metadataLoading.set(false); this.metadataError.set(err?.error?.message || 'No se pudieron cargar categorías y marcas.'); }
    });
    this.loadAlmacenes();
  }

  protected loadItems(): void {
    this.listSub?.unsubscribe();
    this.items.set([]); this.totalItems.set(0); this.totalPages.set(0); this.isLoading.set(false);
    if (!this.section || this.section === 'carga-tecnica' || (!this.design && ['envios','pedidos','resenas','contenido','compatibilidad','calidad','importacion','postventa','reportes'].includes(this.section))) return;

    this.isLoading.set(true);
    this.serverError.set(null);

    const fetchStream = () => {
      if (this.design) {
        return this.adminService.getPreviewItems(
          this.section,
          this.searchTerm(),
          this.currentPage(),
          this.pageSize(),
          this.statusFilter(),
          this.categoriaFilter()
        );
      }
      if (this.section === 'inventario' && typeof this.adminService.getInventoryReal === 'function') {
        return this.adminService.getInventoryReal(
          this.searchTerm(),
          this.currentPage(),
          this.pageSize(),
          this.statusFilter()
        );
      }
      return this.adminService.getItems(
        this.section,
        this.searchTerm(),
        this.currentPage(),
        this.pageSize(),
        this.statusFilter(),
        this.categoriaFilter()
      );
    };

    this.listSub = fetchStream().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (result: AdminPagedResult) => {
        this.items.set(result.items);
        this.totalItems.set(result.total);
        this.totalPages.set(result.totalPages);
        this.currentPage.set(result.page);
        this.isLoading.set(false);
      },
      error: err => {
        this.isLoading.set(false);
        this.serverError.set(err?.error?.message || 'No se pudo consultar esta sección. Reintenta la conexión.');
      }
    });
  }

  protected onSearchInput(val: string): void {
    this.listSub?.unsubscribe(); this.items.set([]); this.totalItems.set(0); this.totalPages.set(0); this.isLoading.set(true);
    this.searchTerm.set(val);
    this.searchSubject.next({value:val,section:this.section});
  }

  protected clearSearch(): void {
    this.searchTerm.set('');
    this.onSearchInput('');
  }

  protected resetFilters(): void {
    this.statusFilter.set('');
    this.categoriaFilter.set('');
    this.onSearchInput('');
  }

  protected setStatusFilter(status: string): void {
    this.statusFilter.set(status);
    this.currentPage.set(0);
    this.loadItems();
  }

  protected setCategoriaFilter(catId: string): void {
    this.categoriaFilter.set(catId);
    this.currentPage.set(0);
    this.loadItems();
  }

  protected toggleFilterMenu(): void {
    this.filterMenuOpen.update(v => !v);
  }

  protected closeFilterMenu(): void {
    this.filterMenuOpen.set(false);
  }

  protected getActiveFilterCount(): number {
    let count = 0;
    if (this.statusFilter()) count++;
    if (this.categoriaFilter()) count++;
    return count;
  }

  protected changePageSize(size: string | number): void {
    const s = Number(size) || 10;
    this.pageSize.set(s);
    this.currentPage.set(0);
    this.loadItems();
  }

  protected goToPage(page: number): void {
    if (page >= 0 && page < this.totalPages()) {
      this.currentPage.set(page);
      this.loadItems();
    }
  }

  protected getEndIndex(): number {
    return Math.min((this.currentPage() + 1) * this.pageSize(), this.totalItems());
  }

  // --- Manejo del Formulario Modal ---

  protected openCreateForm(): void {
    if (this.section === 'clientes') {
      document.querySelector<HTMLInputElement>('.search-box input')?.focus();
      return;
    }
    if (this.section === 'asesorias') {
      this.openCreateAsesoria();
      return;
    }
    if (!this.design && this.section !== 'catalogo') return;
    this.isEditing.set(false);
    this.formError.set(null);
    this.detailLoadError.set(null);
    this.isLoadingDetail.set(false);
    this.currentEditRowId = null;
    this.detailSub?.unsubscribe();
    this.resetQuickCreation();
    this.slugEdited = false;

    this.formData.set({
      nombre: '',
      sku: '',
      slug: '',
      marca: '',
      marcaId: '',
      categoria: '',
      categoriaId: '',
      precio: null,
      costoReferencia: null,
      stockInicial: 0,
      estado: 'draft',
      descripcion: '',
      imagenPrincipalUrl: '',
      imagenes: [],
      requiereSerie: false,
      requiereEnvio: true,
    });
    this.showFormModal.set(true);
    setTimeout(() => {
      this.productMediaComponent?.setImages([]);
      this.captureBaseline();
    });
  }

  protected openEditForm(row: AdminRow): void {
    if (this.section === 'asesorias') {
      this.openEditAsesoria(row);
      return;
    }
    if (!this.design && this.section !== 'catalogo') return;
    this.isEditing.set(true);
    this.formError.set(null);
    this.detailLoadError.set(null);
    this.isLoadingDetail.set(false);
    this.currentEditRowId = row.id;
    this.detailSub?.unsubscribe();
    this.resetQuickCreation();
    this.slugEdited = true;

    const raw = row.rawData || {};
    const cat = this.categories().find(c => c.nombre === row.tag || c.slug === raw.categoriaSlug);
    const m = this.brands().find(b => b.nombre === row.subtitle || b.slug === raw.marcaSlug);

    this.formData.set({
      id: row.id,
      nombre: row.title,
      sku: row.code,
      slug: raw.slug || '',
      marca: row.subtitle || '',
      marcaId: m ? m.id : (raw.marcaId || ''),
      categoria: row.tag || '',
      categoriaId: cat ? cat.id : (raw.categoriaId || ''),
      precio: raw.precioBase ?? raw.precio ?? null,
      costoReferencia: raw.costoReferencia ?? null,
      stockInicial: raw.stockDisponible ?? raw.stockInicial ?? 0,
      estado: row.status === 'draft' ? 'draft' : (row.status === 'inactive' ? 'inactive' : 'active'),
      descripcion: raw.descripcion || '',
      imagenPrincipalUrl: row.thumbnail || raw.imagenPrincipal || '',
      imagenes: raw.imagenes || [],
      requiereSerie: raw.requiereSerie,
      requiereEnvio: raw.requiereEnvio,
      tipo: raw.tipo,
      fichaTecnica: raw.fichaTecnica,
    });
    this.showFormModal.set(true);

    const initialGallery = raw.imagenes && raw.imagenes.length > 0
      ? raw.imagenes
      : (row.thumbnail || raw.imagenPrincipal || raw.imagenPrincipalUrl
          ? [{ url: row.thumbnail || raw.imagenPrincipal || raw.imagenPrincipalUrl, alt: row.title, primary: true }]
          : []);

    setTimeout(() => {
      this.productMediaComponent?.setImages(initialGallery);
      this.captureBaseline();
    });

    if (row.id && this.section === 'catalogo' && !this.design) {
      const rowId = row.id;
      this.isLoadingDetail.set(true);
      this.detailSub = this.adminService.getProductDetail(rowId)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: detail => {
            if (this.currentEditRowId !== rowId) return;
            this.isLoadingDetail.set(false);
            if (!detail) {
              this.detailLoadError.set('No se pudo obtener el detalle del producto.');
              return;
            }
            const catDet = this.categories().find(c => c.nombre === detail.categoria || c.slug === detail.categoriaSlug);
            const marcaDet = this.brands().find(b => b.nombre === detail.marca || b.slug === detail.marcaSlug);
            this.formData.update(data => ({
              ...data,
              nombre: detail.nombre ?? data.nombre,
              sku: detail.sku ?? data.sku,
              slug: detail.slug ?? data.slug,
              descripcion: detail.descripcion ?? '',
              precio: detail.precioBase ?? data.precio,
              costoReferencia: detail.costoReferencia !== undefined ? detail.costoReferencia : data.costoReferencia,
              categoriaId: catDet ? catDet.id : data.categoriaId,
              categoria: detail.categoria ?? data.categoria,
              marcaId: marcaDet ? marcaDet.id : data.marcaId,
              marca: detail.marca ?? data.marca,
              stockInicial: detail.stockDisponible ?? data.stockInicial,
              requiereSerie: detail.requiereSerie,
              requiereEnvio: detail.requiereEnvio,
              tipo: detail.tipo,
              fichaTecnica: detail.fichaTecnica,
              estado: detail.activo && detail.vendible ? 'active' : 'draft',
            }));

            if (detail.imagenes) {
              this.productMediaComponent?.setImages(detail.imagenes);
            }
            this.captureBaseline();
            if (this.dockFocus) {
              const focus = this.dockFocus; this.dockFocus = '';
              setTimeout(() => {
                if (this.currentEditRowId !== rowId || !this.showFormModal()) return;
                const target = document.getElementById(focus === 'imagenes' ? 'admin-product-images' : focus === 'ficha' ? 'admin-product-technical' : '');
                target?.scrollIntoView({block:'start'}); target?.focus({preventScroll:true});
              });
            }
          },
          error: err => {
            if (this.currentEditRowId !== rowId) return;
            this.isLoadingDetail.set(false);
            this.detailLoadError.set(err?.error?.message || err?.message || 'Error al obtener la información completa del producto desde el servidor.');
          }
        });
    }
  }

  protected retryLoadDetail(): void {
    if (!this.currentEditRowId || this.section !== 'catalogo' || this.design) return;
    const rowId = this.currentEditRowId;
    this.isLoadingDetail.set(true);
    this.detailLoadError.set(null);
    this.detailSub?.unsubscribe();
    this.detailSub = this.adminService.getProductDetail(rowId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: detail => {
          if (this.currentEditRowId !== rowId) return;
          this.isLoadingDetail.set(false);
          if (!detail) {
            this.detailLoadError.set('No se pudo obtener el detalle del producto.');
            return;
          }
          const catDet = this.categories().find(c => c.nombre === detail.categoria || c.slug === detail.categoriaSlug);
          const marcaDet = this.brands().find(b => b.nombre === detail.marca || b.slug === detail.marcaSlug);
          this.formData.update(data => ({
            ...data,
            nombre: detail.nombre ?? data.nombre,
            sku: detail.sku ?? data.sku,
            slug: detail.slug ?? data.slug,
            descripcion: detail.descripcion ?? '',
            precio: detail.precioBase ?? data.precio,
            costoReferencia: detail.costoReferencia !== undefined ? detail.costoReferencia : data.costoReferencia,
            categoriaId: catDet ? catDet.id : data.categoriaId,
            categoria: detail.categoria ?? data.categoria,
            marcaId: marcaDet ? marcaDet.id : data.marcaId,
            marca: detail.marca ?? data.marca,
            stockInicial: detail.stockDisponible ?? data.stockInicial,
            requiereSerie: detail.requiereSerie,
            requiereEnvio: detail.requiereEnvio,
            tipo: detail.tipo,
            fichaTecnica: detail.fichaTecnica,
            estado: detail.activo && detail.vendible ? 'active' : 'draft',
          }));

          if (detail.imagenes) {
            this.productMediaComponent?.setImages(detail.imagenes);
          }
          this.captureBaseline();
        },
        error: err => {
          if (this.currentEditRowId !== rowId) return;
          this.isLoadingDetail.set(false);
          this.detailLoadError.set(err?.error?.message || err?.message || 'Error al obtener la información completa del producto desde el servidor.');
        }
      });
  }

  protected onNombreChange(nombre: string): void {
    this.formData.update(data => {
      const slugAuto = nombre
        .toLowerCase()
        .normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '')
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '');
      return {
        ...data,
        nombre,
        slug: !this.slugEdited ? slugAuto : data.slug,
      };
    });
  }

  protected onSlugChange(slug: string): void {
    this.slugEdited = true;
    this.formData.update(data => ({...data, slug}));
  }

  private resetQuickCreation(): void {
    this.isCreatingCategory.set(false); this.isCreatingBrand.set(false);
    this.categoryError.set(null); this.brandError.set(null);
    this.newCategoryName.set(''); this.newCategoryAcronym.set(''); this.newCategoryParentId.set('');
    this.newBrandName.set(''); this.newBrandLogoUrl.set('');
  }

  private slugify(value: string): string {
    return value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
  }

  protected onCategorySelect(event: Event): void {
    if(this.technicalFieldManager && !this.technicalFieldManager.canLeave()){(event.target as HTMLSelectElement).value=this.formData().categoriaId??'';return;}
    const val = (event.target as HTMLSelectElement).value;
    if (val === '__NEW__') {
      this.isCreatingCategory.set(true);
      return;
    }
    const cat = this.categories().find(c => c.id === val || c.nombre === val);
    const ficha=this.formData().fichaTecnica;
    const allowed=new Set(cat?.fichaEsquema?.campos.map(field=>field.key)??[]);
    if(ficha && (ficha.tipo!==cat?.fichaEsquema?.tipo || Object.keys(ficha.valores).some(key=>!allowed.has(key)))){
      if(!window.confirm('La nueva categoría usa otros campos técnicos. ¿Descartar los campos que no le corresponden? Los campos compartidos se conservan y los datos certificados del configurador no se modificarán.')){(event.target as HTMLSelectElement).value=this.formData().categoriaId??'';return;}
      this.formData.update(data=>({...data,fichaTecnica:ficha.tipo===cat?.fichaEsquema?.tipo?{...ficha,valores:Object.fromEntries(Object.entries(ficha.valores).filter(([key])=>allowed.has(key)))}:undefined}));
    }
    this.formData.update(data => ({
      ...data,
      categoriaId: cat ? cat.id : '',
      categoria: cat ? cat.nombre : val,
    }));
  }

  protected toggleCreateCategory(): void {
    if (this.savingReference()) return;
    this.categoryError.set(null);
    this.isCreatingCategory.update(v => !v);
    this.newCategoryName.set('');
    this.newCategoryAcronym.set('');
    this.newCategoryParentId.set('');
  }

  protected submitNewCategory(): void {
    if (this.savingReference() || this.isSaving()) return;
    this.categoryError.set(null);
    const nombre = this.newCategoryName().trim();
    if (!nombre || nombre.length > 120) {
      this.categoryError.set('Ingresa un nombre de categoría de hasta 120 caracteres.');
      return;
    }
    if (this.categories().some(c => this.slugify(c.nombre) === this.slugify(nombre))) {
      this.categoryError.set('Esa categoría ya existe. Selecciónala en la lista.'); return;
    }
    const acronimo = this.newCategoryAcronym().trim();
    const parentId = this.newCategoryParentId().trim() || undefined;
    if (acronimo.length > 20 || (parentId && !this.categories().some(c => c.id === parentId))) {
      this.categoryError.set('Revisa el acrónimo y la categoría superior.'); return;
    }
    if (!this.design && (this.metadataLoading() || this.metadataError())) {
      this.categoryError.set('Carga las categorías antes de crear una nueva.'); return;
    }
    if(this.formData().fichaTecnica && !window.confirm('Crear y seleccionar otra categoría reiniciará la ficha técnica del formulario. ¿Continuar?'))return;

    if (this.design) {
      const slug = this.slugify(nombre);
      const localCat = { id: `local-cat-${Date.now()}`, nombre, slug, parentId };
      this.categories.update(list => [...list, localCat]);
      this.formData.update(data => ({
        ...data,
        categoriaId: localCat.id,
        categoria: localCat.nombre,
        fichaTecnica: undefined,
      }));
      this.isCreatingCategory.set(false);
      this.newCategoryParentId.set('');
      this.notifications.show(`[Modo diseño] Categoría "${nombre}" agregada localmente.`, 'info');
      return;
    }

    this.savingCategory.set(true);
    this.adminService.createCategory({ nombre, acronimo, parentId, slug: this.slugify(nombre) })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: created => {
          this.savingCategory.set(false);
          this.categories.update(list => [...list, created]);
          this.formData.update(data => ({
            ...data,
            categoriaId: created.id,
            categoria: created.nombre,
            fichaTecnica: undefined,
          }));
          this.isCreatingCategory.set(false);
          this.newCategoryParentId.set('');
          this.notifications.show(`Categoría "${created.nombre}" creada y seleccionada.`, 'success');
        },
        error: err => {
          this.savingCategory.set(false);
          this.categoryError.set(err?.error?.message || 'No se pudo crear la categoría. Puedes reintentar sin perder los datos.');
          this.notifications.show(err?.error?.message || 'Error al crear la categoría en el servidor.', 'error');
        }
      });
  }

  protected onBrandSelect(event: Event): void {
    const val = (event.target as HTMLSelectElement).value;
    if (val === '__NEW__') {
      this.isCreatingBrand.set(true);
      return;
    }
    const brand = this.brands().find(b => b.id === val || b.nombre === val);
    this.formData.update(data => ({
      ...data,
      marcaId: brand ? brand.id : '',
      marca: brand ? brand.nombre : val,
    }));
  }

  protected toggleCreateBrand(): void {
    if (this.savingReference()) return;
    this.brandError.set(null);
    this.isCreatingBrand.update(v => !v);
    this.newBrandName.set('');
    this.newBrandLogoUrl.set('');
  }

  protected submitNewBrand(): void {
    if (this.savingReference() || this.isSaving()) return;
    this.brandError.set(null);
    const nombre = this.newBrandName().trim();
    if (!nombre || nombre.length > 130) {
      this.brandError.set('Ingresa un nombre de marca de hasta 130 caracteres.');
      return;
    }
    if (this.brands().some(b => this.slugify(b.nombre) === this.slugify(nombre))) {
      this.brandError.set('Esa marca ya existe. Selecciónala en la lista.'); return;
    }
    const logoUrl = this.newBrandLogoUrl().trim();
    if (logoUrl) {
      try { const url = new URL(logoUrl); if (url.protocol !== 'https:' || url.username || url.password) throw new Error(); }
      catch { this.brandError.set('El logo debe usar un enlace HTTPS válido, sin credenciales.'); return; }
    }
    if (!this.design && (this.metadataLoading() || this.metadataError())) {
      this.brandError.set('Carga las marcas antes de crear una nueva.'); return;
    }

    if (this.design) {
      const slug = this.slugify(nombre);
      const localBrand = { id: `local-marca-${Date.now()}`, nombre, slug };
      this.brands.update(list => [...list, localBrand]);
      this.formData.update(data => ({
        ...data,
        marcaId: localBrand.id,
        marca: localBrand.nombre,
      }));
      this.isCreatingBrand.set(false);
      this.notifications.show(`[Modo diseño] Marca "${nombre}" agregada localmente.`, 'info');
      return;
    }

    this.savingBrand.set(true);
    this.adminService.createBrand({ nombre, logoUrl: logoUrl || undefined, slug: this.slugify(nombre) })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: created => {
          this.savingBrand.set(false);
          this.brands.update(list => [...list, created]);
          this.formData.update(data => ({
            ...data,
            marcaId: created.id,
            marca: created.nombre,
          }));
          this.isCreatingBrand.set(false);
          this.notifications.show(`Marca "${created.nombre}" creada y seleccionada.`, 'success');
        },
        error: err => {
          this.savingBrand.set(false);
          this.brandError.set(err?.error?.message || 'No se pudo crear la marca. Puedes reintentar sin perder los datos.');
          this.notifications.show(err?.error?.message || 'Error al crear la marca en el servidor.', 'error');
        }
      });
  }

  private captureBaseline(): void {
    this.formBaseline = JSON.stringify(this.formData());
    this.galleryBaseline = JSON.stringify(this.productMediaComponent?.snapshot() ?? []);
  }

  canLeave(): boolean {
    if (this.pricingAdmin && !this.pricingAdmin.canLeave()) return false;
    if (this.technicalFieldManager && !this.technicalFieldManager.canLeave()) return false;
    if (this.referencesEditor && !this.referencesEditor.canLeave()) return false;
    if (this.operationsAdmin && !this.operationsAdmin.canLeave()) return false;
    if (this.productImporter && !this.productImporter.canLeave()) return false;
    if (this.shippingAdmin && !this.shippingAdmin.canLeave()) return false;
    if (this.importBusy()) { this.notifications.show('Espera a que termine la importación.', 'info'); return false; }
    if (this.stockUncertain()) { this.notifications.show('Reintenta el movimiento pendiente antes de salir para no duplicarlo.', 'info'); return false; }
    if (this.isSaving() || this.savingReference() || this.isSavingStock() || this.pendingRows().size > 0) { this.notifications.show('Espera a que termine el guardado.', 'info'); return false; }
    if (this.technicalEditor && !this.technicalEditor.canLeave()) return false;
    if (!this.showFormModal()) return true;
    return this.closeForm();
  }

  protected closeForm(): boolean {
    if(this.technicalFieldManager && !this.technicalFieldManager.canLeave())return false;
    if (this.isSaving() || this.savingReference() || this.customFieldsBusy()) {
      return false;
    }
    const dirty = JSON.stringify(this.formData()) !== this.formBaseline || JSON.stringify(this.productMediaComponent?.snapshot() ?? []) !== this.galleryBaseline || (this.isCreatingCategory() && !!this.newCategoryName().trim()) || (this.isCreatingBrand() && !!this.newBrandName().trim());
    if (dirty && !window.confirm('Hay cambios sin guardar. ¿Quieres descartarlos?')) return false;
    this.detailSub?.unsubscribe();
    this.currentEditRowId = null;
    this.isLoadingDetail.set(false);
    this.detailLoadError.set(null);
    if (this.productMediaComponent) {
      this.productMediaComponent.cleanupStagedUploads();
    }
    this.showFormModal.set(false);
    this.formError.set(null);
    return true;
  }

  protected saveAsDraft(form?: NgForm): void {
    if (this.isSaving() || this.savingReference() || this.customFieldsBusy()) return;
    this.formData.update(data => ({ ...data, estado: 'draft' }));
    this.submitForm(form);
  }

  protected submitForm(form?: NgForm): void {
    if(this.customFieldsBusy())return;
    if(this.technicalFieldManager?.hasPendingChanges()){this.formError.set('Guarda el campo técnico adicional o limpia su editor antes de guardar el producto.');return;}
    if (this.isSaving() || this.savingReference() || this.customFieldsBusy()) return;
    if (!this.design && this.section !== 'catalogo') return;
    if (form?.invalid) { form.control.markAllAsTouched(); this.formError.set('Revisa los campos obligatorios y los valores numéricos.'); return; }
    if (this.technicalForm && !this.technicalForm.canSave()) {this.formError.set('Revisa los valores de la ficha técnica del componente.');return;}
    if (this.isLoadingDetail()) {
      this.notifications.show('Espera a que termine de cargarse el detalle del producto antes de guardar.', 'info');
      return;
    }
    if (this.detailLoadError()) {
      this.formError.set('No se puede guardar porque falló la carga del detalle del producto. Reintenta la carga antes de continuar.');
      return;
    }
    const data = { ...this.formData() };
    if (!['active', 'draft', 'inactive'].includes(data.estado)) {
      this.formError.set('Selecciona un estado de publicación válido.'); return;
    }
    if (data.precio == null || !Number.isFinite(data.precio) || data.precio < 0) {
      this.formError.set('Ingresa un precio válido; dejarlo vacío no equivale a cero.'); return;
    }
    if (data.costoReferencia != null && (!Number.isFinite(data.costoReferencia) || data.costoReferencia < 0)) {
      this.formError.set('El costo debe ser un número no negativo.'); return;
    }
    if (!this.isEditing() && (data.stockInicial == null || !Number.isInteger(data.stockInicial) || data.stockInicial < 0)) {
      this.formError.set('Confirma el stock inicial con un entero no negativo.'); return;
    }
    if (!this.design && (this.metadataLoading() || this.metadataError())) {
      this.formError.set('Carga las categorías y marcas antes de guardar.'); return;
    }
    if (!data.nombre?.trim() || !data.sku?.trim()) {
      this.formError.set('Por favor completa los campos obligatorios: Nombre y SKU.');
      return;
    }
    if (data.nombre.trim().length > 220 || data.sku.trim().length > 80 || (data.slug && !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(data.slug))) {
      this.formError.set('Revisa el nombre (máximo 220), el SKU (máximo 80) y el slug (minúsculas, números y guiones).'); return;
    }
    if (!data.categoriaId || !this.categories().some(c => c.id === data.categoriaId)) {
      this.formError.set('Selecciona una categoría existente o crea una antes de guardar.'); return;
    }
    if (data.marcaId && !this.brands().some(b => b.id === data.marcaId)) {
      this.formError.set('La marca seleccionada ya no está disponible. Revisa las opciones.'); return;
    }

    if (this.productMediaComponent) {
      if (this.productMediaComponent.hasPendingUploads()) {
        this.notifications.show('Espera a que terminen de subirse las imágenes antes de guardar.', 'info');
        return;
      }
      if (this.productMediaComponent.hasPendingOrFailedUploads()) {
        this.formError.set('Hay imágenes con error de subida o en formato no persistido. Corrige o retira las imágenes fallidas antes de guardar.');
        this.notifications.show('Corrige o retira las imágenes con error antes de guardar.', 'error');
        return;
      }
      const galleryPayload = this.productMediaComponent.getPayload();
      data.imagenes = galleryPayload;
      if (galleryPayload.length > 0) {
        data.imagenPrincipalUrl = galleryPayload[0].url;
      } else {
        data.imagenPrincipalUrl = '';
      }
    }

    this.isSaving.set(true);
    this.formError.set(null);

    if (this.design) {
      this.adminService.saveItemLocally(this.section, data, this.isEditing())
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe(response => {
          this.isSaving.set(false);
          this.showFormModal.set(false);
          this.notifications.show(`[Modo diseño] ${response.message}`, 'info');
          this.loadItems();
          this.loadStats();
        });
      return;
    }

    this.productMediaComponent?.beginPersistenceAttempt();
    this.adminService.saveItem(this.section, data, this.isEditing())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: response => {
          this.productMediaComponent?.commitStagedUploads();
          this.isSaving.set(false);
          this.showFormModal.set(false);
          this.notifications.show(response.message, 'success');
          this.loadItems();
          this.loadStats();
        },
        error: err => {
          this.productMediaComponent?.persistenceFailed(err?.status);
          this.isSaving.set(false);
          this.formError.set(err?.error?.message || err?.message || 'Error al guardar el registro en el servidor.');
          this.notifications.show(err?.error?.message || 'Error al guardar el registro en el servidor.', 'error');
        }
      });
  }

  protected toggleRowStatus(row: AdminRow): void {
    if (this.section === 'asesorias') {
      if (row.status === 'active') {
        this.deactivateAsesoria(row);
      } else {
        this.publishAsesoria(row);
      }
      return;
    }
    if (this.design) {
      const nextStatus = row.status === 'active' ? 'inactive' : 'active';
      this.adminService.updateLocalStatus(this.section, row.id, nextStatus, nextStatus === 'active' ? 'Activo' : 'Inactivo');
      this.notifications.show('[Modo diseño] Estado actualizado localmente.', 'info');
      this.loadItems();
      return;
    }
    this.adminService.toggleStatus(this.section, row.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: res => {
          this.notifications.show(res.message, 'info');
          this.loadItems();
        },
        error: err => {
          this.notifications.show(err?.error?.message || 'Error al cambiar estado en el servidor.', 'error');
        }
      });
  }

  protected toggleUserRole(row: AdminRow): void {
    const currentRole = row.rawData?.rol || (row.status === 'active' ? 'admin' : 'user');
    const targetRole: 'admin' | 'user' = currentRole === 'admin' ? 'user' : 'admin';
    const roleLabel = targetRole === 'admin' ? 'Administrador' : 'Usuario';

    if (currentRole === 'admin' && this.auth.user()?.id === row.id) {
      const confirmSelf = confirm('Estás a punto de quitarte los permisos de administrador. Perderás el acceso a este panel en tu próxima sesión. ¿Deseas continuar?');
      if (!confirmSelf) return;
    }

    const nextPending = new Set(this.pendingRows());
    nextPending.add(row.id);
    this.pendingRows.set(nextPending);

    this.adminService.updateUserRole(row.id, targetRole)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          const updatedSet = new Set(this.pendingRows());
          updatedSet.delete(row.id);
          this.pendingRows.set(updatedSet);

          this.notifications.show(`El rol de ${row.title} se actualizó a ${roleLabel}.`, 'success');
          this.items.update(list => list.map(item => {
            if (item.id === row.id) {
              const rawData = { ...item.rawData, rol: targetRole };
              return {
                ...item,
                code: targetRole === 'admin' ? 'ADMIN' : 'USUARIO',
                status: targetRole === 'admin' ? 'active' : 'draft',
                statusText: targetRole === 'admin' ? 'Administrador' : 'Usuario',
                tag: targetRole === 'admin' ? 'Administrador' : 'Cliente',
                rawData,
              };
            }
            return item;
          }));
        },
        error: err => {
          const updatedSet = new Set(this.pendingRows());
          updatedSet.delete(row.id);
          this.pendingRows.set(updatedSet);
          this.notifications.show(err?.error?.message || 'No se pudo actualizar el rol del usuario.', 'error');
        }
      });
  }

  // --- Operaciones de Asesorías ---

  protected openCreateAsesoria(): void {
    this.isEditingAsesoria.set(false);
    this.asesoriaFormError.set(null);
    this.isCreatingFamilia.set(false);
    this.newFamiliaNombre.set('');
    this.newFamiliaCodigo.set('');
    this.loadFamiliasAsesoria();

    this.asesoriaFormData.set({
      familiaId: this.familiasAsesoria()[0]?.id || '',
      codigo: '',
      nombre: '',
      descripcion: '',
      prioridadCpu: 5,
      prioridadGpu: 5,
      ramMinimaGb: 16,
      requiereGpuDedicada: false,
      presupuestoSugeridoMin: 2500,
      presupuestoSugeridoMax: 6000,
      activo: true,
      reglasPresupuesto: [
        { tipoComponenteCodigo: 'CPU', prioridad: 1, obligatorio: true },
        { tipoComponenteCodigo: 'GPU', prioridad: 2, obligatorio: false },
        { tipoComponenteCodigo: 'RAM', prioridad: 3, obligatorio: true },
        { tipoComponenteCodigo: 'PLACA', prioridad: 4, obligatorio: true },
        { tipoComponenteCodigo: 'FUENTE', prioridad: 5, obligatorio: true },
        { tipoComponenteCodigo: 'GABINETE', prioridad: 6, obligatorio: true },
      ],
      necesidades: [],
    });
    this.showAsesoriaModal.set(true);
  }

  protected readonly prioridadesDisponibles = [1, 2, 3, 4, 5, 6];

  protected isPrioridadOcupada(prioridad: number, currentIndex: number): boolean {
    const reglas = this.asesoriaFormData().reglasPresupuesto || [];
    return reglas.some((r, idx) => idx !== currentIndex && Number(r.prioridad) === Number(prioridad));
  }

  protected getPrioridadTexto(p: number): string {
    switch (p) {
      case 1: return '(Máxima importancia)';
      case 2: return '(Alta importancia)';
      case 3: return '(Media-Alta)';
      case 4: return '(Media)';
      case 5: return '(Moderada)';
      case 6: return '(Básica)';
      default: return '';
    }
  }

  protected getSiguientePrioridadLibre(): number {
    const reglas = this.asesoriaFormData().reglasPresupuesto || [];
    const ocupadas = new Set(reglas.map(r => Number(r.prioridad)));
    for (const p of this.prioridadesDisponibles) {
      if (!ocupadas.has(p)) return p;
    }
    return 6;
  }

  protected openEditAsesoria(row: AdminRow): void {
    this.isEditingAsesoria.set(true);
    this.asesoriaFormError.set(null);
    this.loadFamiliasAsesoria();

    const raw = row.rawData || {};
    this.asesoriaFormData.set({
      id: row.id,
      familiaId: raw.familiaId || '',
      codigo: raw.codigo || row.code,
      nombre: raw.nombre || row.title,
      descripcion: raw.descripcion || '',
      prioridadCpu: raw.prioridadCpu ?? 5,
      prioridadGpu: raw.prioridadGpu ?? 5,
      ramMinimaGb: raw.ramMinimaGb ?? 16,
      requiereGpuDedicada: Boolean(raw.requiereGpuDedicada),
      presupuestoSugeridoMin: raw.presupuestoSugeridoMin ?? 0,
      presupuestoSugeridoMax: raw.presupuestoSugeridoMax ?? 0,
      activo: raw.activo ?? (row.status === 'active'),
      reglasPresupuesto: (raw.reglasPresupuesto || []).map((r: any, idx: number) => ({
        ...r,
        prioridad: r.prioridad != null ? Number(r.prioridad) : (idx + 1)
      })),
      necesidades: (raw.necesidades || []).map((n: any) => ({ ...n })),
    });
    this.showAsesoriaModal.set(true);
  }

  protected closeAsesoriaModal(): void {
    if (this.isSavingAsesoria()) return;
    this.showAsesoriaModal.set(false);
  }

  protected loadFamiliasAsesoria(): void {
    this.adminService.getFamiliasAsesoria()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: list => {
          this.familiasAsesoria.set(list || []);
          if (list.length > 0 && !this.asesoriaFormData().familiaId) {
            this.asesoriaFormData.update(f => ({ ...f, familiaId: list[0].id }));
          }
        },
        error: () => {}
      });
  }

  protected toggleCreateFamilia(): void {
    this.isCreatingFamilia.update(v => !v);
    this.familiaError.set(null);
  }

  protected submitNewFamilia(): void {
    const nombre = this.newFamiliaNombre().trim();
    if (!nombre) {
      this.familiaError.set('Ingresa un nombre para la familia.');
      return;
    }
    const codigo = (this.newFamiliaCodigo().trim() || this.slugify(nombre)).toUpperCase().replace(/[^A-Z0-9_]/g, '_');
    this.savingFamilia.set(true);
    this.adminService.createFamiliaAsesoria({
      nombre,
      codigo,
      icono: this.newFamiliaIcono(),
    }).pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: created => {
          this.savingFamilia.set(false);
          this.familiasAsesoria.update(list => [...list, created]);
          this.asesoriaFormData.update(f => ({ ...f, familiaId: created.id }));
          this.isCreatingFamilia.set(false);
          this.notifications.show(`Familia "${created.nombre}" creada y seleccionada.`, 'success');
        },
        error: err => {
          this.savingFamilia.set(false);
          this.familiaError.set(err?.error?.message || 'Error al crear la familia.');
        }
      });
  }

  protected addReglaPresupuesto(): void {
    if (this.asesoriaFormData().reglasPresupuesto.length >= 6) {
      this.notifications.show('Solo se pueden configurar hasta 6 componentes (prioridades del 1 al 6).', 'info');
      return;
    }
    const prio = this.getSiguientePrioridadLibre();
    this.asesoriaFormData.update(f => ({
      ...f,
      reglasPresupuesto: [
        ...f.reglasPresupuesto,
        { tipoComponenteCodigo: 'OTRO', prioridad: prio, obligatorio: false }
      ]
    }));
  }

  protected removeReglaPresupuesto(index: number): void {
    this.asesoriaFormData.update(f => {
      const next = [...f.reglasPresupuesto];
      next.splice(index, 1);
      return { ...f, reglasPresupuesto: next };
    });
  }

  protected addNecesidad(): void {
    this.asesoriaFormData.update(f => ({
      ...f,
      necesidades: [
        ...f.necesidades,
        { nombre: '', descripcion: '' }
      ]
    }));
  }

  protected removeNecesidad(index: number): void {
    this.asesoriaFormData.update(f => {
      const next = [...f.necesidades];
      next.splice(index, 1);
      return { ...f, necesidades: next };
    });
  }

  protected submitAsesoriaForm(): void {
    if (this.isSavingAsesoria()) return;
    const form = this.asesoriaFormData();
    if (!form.nombre.trim()) {
      this.asesoriaFormError.set('El nombre del perfil es obligatorio.');
      return;
    }
    if (!form.familiaId) {
      this.asesoriaFormError.set('Debes seleccionar o crear una familia de uso.');
      return;
    }
    if (!form.codigo.trim()) {
      form.codigo = this.slugify(form.nombre).toUpperCase().replace(/[^A-Z0-9_]/g, '_');
    }

    // Validar que no se repitan niveles de prioridad entre componentes (1-6)
    if (form.reglasPresupuesto && form.reglasPresupuesto.length > 0) {
      const prioridades = form.reglasPresupuesto.map(r => Number(r.prioridad));
      const unicas = new Set(prioridades);
      if (unicas.size !== prioridades.length) {
        this.asesoriaFormError.set('No se puede aplicar el mismo nivel de prioridad a dos componentes.');
        return;
      }
      if (prioridades.some(p => !p || p < 1 || p > 6)) {
        this.asesoriaFormError.set('Cada componente debe tener asignado un nivel de prioridad entre 1 y 6.');
        return;
      }
    }

    this.isSavingAsesoria.set(true);
    this.asesoriaFormError.set(null);

    this.adminService.savePerfilAsesoria(form as any)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: res => {
          this.isSavingAsesoria.set(false);
          this.showAsesoriaModal.set(false);
          this.notifications.show(`Perfil "${res.nombre}" guardado correctamente.`, 'success');
          this.loadItems();
        },
        error: err => {
          this.isSavingAsesoria.set(false);
          this.asesoriaFormError.set(err?.error?.message || 'Error al guardar el perfil en el servidor.');
        }
      });
  }

  protected publishAsesoria(row: AdminRow): void {
    if (this.pendingRows().has(row.id)) return;
    this.pendingRows.update(rows => new Set([...rows, row.id]));
    this.adminService.publishPerfilAsesoria(row.id)
      .pipe(takeUntilDestroyed(this.destroyRef), finalize(() => {
        this.pendingRows.update(rows => { const next = new Set(rows); next.delete(row.id); return next; });
      }))
      .subscribe({
        next: () => {
          this.notifications.show(`Perfil activado.`, 'success');
          this.loadItems();
        },
        error: err => this.notifications.show(err?.error?.message || 'Error al activar perfil.', 'error')
      });
  }

  protected deactivateAsesoria(row: AdminRow): void {
    if (this.pendingRows().has(row.id)) return;
    this.pendingRows.update(rows => new Set([...rows, row.id]));
    this.adminService.deactivatePerfilAsesoria(row.id)
      .pipe(takeUntilDestroyed(this.destroyRef), finalize(() => {
        this.pendingRows.update(rows => { const next = new Set(rows); next.delete(row.id); return next; });
      }))
      .subscribe({
        next: () => {
          this.notifications.show(`Perfil desactivado.`, 'info');
          this.loadItems();
        },
        error: err => this.notifications.show(err?.error?.message || 'Error al desactivar perfil.', 'error')
      });
  }

  protected deleteAsesoria(row: AdminRow): void {
    if (this.pendingRows().has(row.id)) return;
    if (!confirm(`¿Eliminar el perfil "${row.title}" de asesorías?`)) return;
    this.pendingRows.update(rows => new Set([...rows, row.id]));
    this.adminService.deletePerfilAsesoria(row.id)
      .pipe(takeUntilDestroyed(this.destroyRef), finalize(() => {
        this.pendingRows.update(rows => { const next = new Set(rows); next.delete(row.id); return next; });
      }))
      .subscribe({
        next: () => {
          this.notifications.show('Perfil eliminado.', 'success');
          this.loadItems();
        },
        error: err => this.notifications.show(err?.error?.message || 'Error al eliminar perfil.', 'error')
      });
  }

  protected publishProduct(row: AdminRow): void {
    if (this.pendingRows().has(row.id)) return;
    if (this.design) {
      this.adminService.updateLocalStatus('catalogo', row.id, 'active', 'Activo');
      this.notifications.show('[Modo diseño] Producto marcado como publicado.', 'info');
      this.loadItems();
      return;
    }
    if (!window.confirm(`¿Publicar "${row.title}" en la tienda? Esto no verifica su compatibilidad técnica.`)) return;
    this.runProductAction(row, this.adminService.publishProduct(row.id));
  }

  protected deactivateProduct(row: AdminRow): void {
    if (this.pendingRows().has(row.id)) return;
    if (this.design) {
      this.adminService.updateLocalStatus('catalogo', row.id, 'inactive', 'Inactivo');
      this.notifications.show('[Modo diseño] Producto desactivado localmente.', 'info');
      this.loadItems();
      return;
    }
    if (!window.confirm(`¿Ocultar "${row.title}" de la tienda?`)) return;
    this.runProductAction(row, this.adminService.deactivateProduct(row.id));
  }

  protected saveDraftProduct(row: AdminRow): void {
    if (this.pendingRows().has(row.id)) return;
    if (this.design) {
      this.adminService.updateLocalStatus('catalogo', row.id, 'draft', 'Borrador');
      this.notifications.show('[Modo diseño] Producto guardado como borrador.', 'info');
      this.loadItems();
      return;
    }
    if (!window.confirm(`¿Guardar "${row.title}" sin publicar? Dejará de estar visible en la tienda.`)) return;
    this.runProductAction(row, this.adminService.saveDraftProduct(row.id));
  }

  protected deleteProduct(row: AdminRow): void {
    if (this.pendingRows().has(row.id)) return;
    if (confirm(`¿Estás seguro de que deseas retirar del catálogo el producto "${row.title}"?`)) {
      if (this.design) {
        this.adminService.deleteLocalItem('catalogo', row.id);
        this.notifications.show('[Modo diseño] Producto eliminado de la vista.', 'info');
        this.loadItems();
        return;
      }
      this.runProductAction(row, this.adminService.deleteItem(this.section, row.id));
    }
  }

  private runProductAction(row: AdminRow, request: Observable<{message:string}>): void {
    this.pendingRows.update(rows => new Set([...rows, row.id]));
    request.pipe(takeUntilDestroyed(this.destroyRef), finalize(() => {
      this.pendingRows.update(rows => { const next = new Set(rows); next.delete(row.id); return next; });
    })).subscribe({
      next: res => { this.notifications.show(res.message, 'success'); this.loadItems(); this.loadStats(); },
      error: err => this.notifications.show(err?.error?.message || 'No se pudo completar la operación. Puedes reintentar.', 'error'),
    });
  }

  // --- Métodos de Inventario y Kardex ---

  protected loadAlmacenes(): void {
    if (this.design) return;
    this.almacenesError.set(null);
    if (this.almacenes().length > 0) return;
    if (typeof this.adminService.getAlmacenes !== 'function') return;
    this.adminService.getAlmacenes().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: list => {
        this.almacenes.set(list || []);
        if (list && list.length > 0 && !this.stockForm().almacenId) {
          this.stockForm.update(f => ({ ...f, almacenId: list[0].id }));
          this.refreshStock();
        }
      },
      error: () => { this.almacenesError.set('No se pudieron cargar los almacenes. Reintenta.'); },
    });
  }

  protected openStockModal(row?: AdminRow): void {
    if (this.isSavingStock()) return;
    if (this.design) { this.notifications.show('Inventario no realiza operaciones en la vista de diseño.', 'info'); return; }
    if (this.stockUncertain()) { this.showStockModal.set(true); return; }
    if (this.restoreStockAttempt()) return;
    this.stockError.set(null);
    this.stockCurrent.set(null);
    this.stockAttempt = null;
    this.stockOperation = crypto.randomUUID();
    this.loadAlmacenes();

    const raw: InventarioItem | undefined = row?.rawData;
    this.selectedStockRow.set(row || null);

    const defaultAlmacenId = raw?.almacenId || (this.almacenes()[0]?.id ?? '');
    const defaultProductoId = raw?.productoId || '';

    this.stockForm.set({
      productoId: defaultProductoId,
      almacenId: defaultAlmacenId,
      tipo: 'ENTRADA',
      cantidad: null,
      nota: '',
    });

    this.showStockModal.set(true);
    if (row) this.refreshStock();
    else this.searchStockProducts();
  }

  protected closeStockModal(): void {
    if (this.isSavingStock() || this.stockUncertain()) return;
    this.stockLookupSub?.unsubscribe();
    this.stockProductsSub?.unsubscribe();
    this.showStockModal.set(false);
    this.selectedStockRow.set(null);
    this.stockError.set(null);
  }

  protected onStockProductChange(val: string): void {
    this.stockForm.update(f => ({ ...f, productoId: val }));
    this.refreshStock();
  }

  protected onStockAlmacenChange(val: string): void {
    this.stockForm.update(f => ({ ...f, almacenId: val }));
    this.refreshStock();
  }

  protected searchStockProducts(page = 0): void {
    if (this.design) return;
    this.stockProductsSub?.unsubscribe();
    this.stockProductsLoading.set(true);
    this.stockProductsError.set(null);
    this.stockProductsSub = this.adminService.getItems('catalogo', this.stockProductQuery(), page, 20, '').pipe(
      takeUntilDestroyed(this.destroyRef), finalize(() => this.stockProductsLoading.set(false))
    ).subscribe({next: res => { this.stockProducts.set(res.items); this.stockProductPage.set(res.page); this.stockProductPages.set(res.totalPages); },
      error: () => this.stockProductsError.set('No se pudieron consultar los productos.')});
  }

  protected refreshStock(): void {
    if (this.design) return;
    if (this.stockUncertain()) return;
    this.stockLookupSub?.unsubscribe();
    this.stockCurrent.set(null);
    this.stockError.set(null);
    this.stockAttempt = null;
    this.stockOperation = crypto.randomUUID();
    const f = this.stockForm();
    if (!f.productoId || !f.almacenId) return;
    this.stockLoading.set(true);
    const variante = this.selectedStockRow()?.rawData?.varianteId;
    this.stockLookupSub = this.adminService.consultarStock(f.productoId, f.almacenId, variante).pipe(
      takeUntilDestroyed(this.destroyRef), finalize(() => this.stockLoading.set(false))
    ).subscribe({next: row => this.stockCurrent.set(row),
      error: err => this.stockError.set(err?.error?.message || 'No se pudo consultar el stock. Reintenta.')});
  }

  protected onStockTipoChange(val: 'ENTRADA' | 'SALIDA' | 'AJUSTE'): void {
    this.stockForm.update(f => ({ ...f, tipo: val }));
  }

  protected onStockCantidadChange(val: any): void {
    const num = val === '' || val == null ? null : Number(val);
    this.stockForm.update(f => ({ ...f, cantidad: num }));
  }

  protected onStockNotaChange(val: string): void {
    this.stockForm.update(f => ({ ...f, nota: val }));
  }

  protected isStockFormValid(): boolean {
    if (this.design || this.stockLoading() || !this.stockCurrent()) return false;
    const f = this.stockForm();
    if (!f.productoId || !f.almacenId || !f.nota.trim()) return false;
    if (f.cantidad == null || f.cantidad < 0 || f.cantidad > 2147483647 || !Number.isInteger(Number(f.cantidad))) return false;
    if (f.nota.length > 2000) return false;
    if (f.tipo === 'ENTRADA' && this.stockCurrent()!.cantidad + f.cantidad > 2147483647) return false;
    if (f.tipo !== 'AJUSTE' && f.cantidad <= 0) return false;

    if (f.tipo === 'SALIDA') {
      const raw = this.stockCurrent();
      const disponible = raw?.disponible ?? 0;
      if (f.cantidad > disponible) return false;
    }

    if (f.tipo === 'AJUSTE') {
      const raw = this.stockCurrent();
      const reservado = raw?.reservado ?? 0;
      if (f.cantidad < reservado) return false;
    }

    return true;
  }

  protected getStockProjectionText(): string {
    const f = this.stockForm();
    const raw = this.stockCurrent();
    if (!raw) return this.stockLoading() ? 'Consultando stock real…' : 'Selecciona producto y almacén y consulta el stock.';
    const actualDisponible = raw?.disponible ?? 0;
    const actualTotal = raw?.cantidad ?? 0;
    const actualReservado = raw?.reservado ?? 0;
    const cant = f.cantidad ?? 0;

    if (f.tipo === 'ENTRADA') {
      const nuevoDisp = actualDisponible + cant;
      const nuevoTotal = actualTotal + cant;
      if (nuevoTotal > 2147483647) return 'Inválido: supera el límite permitido de existencias.';
      return `${nuevoDisp} un. disponibles (${nuevoTotal} un. totales)`;
    } else if (f.tipo === 'SALIDA') {
      if (cant > actualDisponible) {
        return `Inválido: excede unidades disponibles (${actualDisponible} un.)`;
      }
      const nuevoDisp = actualDisponible - cant;
      const nuevoTotal = actualTotal - cant;
      return `${nuevoDisp} un. disponibles (${nuevoTotal} un. totales)`;
    } else if (f.tipo === 'AJUSTE') {
      if (cant < actualReservado) {
        return `Inválido: menor a unidades reservadas (${actualReservado} un.)`;
      }
      const nuevoDisp = Math.max(0, cant - actualReservado);
      return `${nuevoDisp} un. disponibles (${cant} un. totales)`;
    }
    return '—';
  }

  protected submitStockAdjustment(): void {
    const f = this.stockForm();
    if (this.design || this.isSavingStock() || !this.isStockFormValid()) return;

    this.isSavingStock.set(true);
    this.stockError.set(null);

    const payload: AjusteStockPayload = {
      operacionId: this.stockOperation,
      versionEsperada: this.stockCurrent()!.version,
      existenciaId: this.stockCurrent()!.existenciaId || undefined,
      varianteId: this.stockCurrent()!.varianteId || undefined,
      productoId: f.productoId,
      almacenId: f.almacenId,
      tipo: f.tipo,
      cantidad: Number(f.cantidad),
      nota: f.nota.trim(),
    };
    if (this.stockAttempt && JSON.stringify({...this.stockAttempt, operacionId: ''}) !== JSON.stringify({...payload, operacionId: ''})) {
      this.stockError.set('Hay una operación con respuesta incierta. Reintenta sin modificar los datos antes de iniciar otra.');
      this.isSavingStock.set(false);
      return;
    }
    this.stockAttempt = payload;
    const pendingKey = this.stockPendingKey();
    if (pendingKey) {
      try { sessionStorage.setItem(pendingKey, JSON.stringify({payload, current: this.stockCurrent()})); }
      catch { this.stockAttempt=null; this.isSavingStock.set(false); this.stockError.set('No se pudo conservar la operación para un reintento seguro. Habilita el almacenamiento de esta pestaña.'); return; }
    }
    this.adminService.ajustarStock(payload).pipe(
      takeUntilDestroyed(this.destroyRef),
      finalize(() => this.isSavingStock.set(false))
    ).subscribe({
      next: res => {
        this.clearStockAttempt();
        this.stockUncertain.set(false);
        this.stockAttempt = null;
        this.showStockModal.set(false);
        this.notifications.show(`Movimiento registrado. Disponible tras esa operación: ${res.disponible} un. Se actualizará el listado.`);
        this.loadItems();
      },
      error: err => {
        this.stockUncertain.set(!err?.status || err.status >= 500 || [401,403,408,429].includes(err.status));
        if (!this.stockUncertain()) { this.clearStockAttempt(); this.stockAttempt = null; this.stockOperation = crypto.randomUUID(); }
        if (err?.status === 409) this.stockCurrent.set(null);
        const msg = err?.error?.message || err?.message || 'Error al registrar el movimiento en el servidor.';
        this.stockError.set(this.stockUncertain() ? 'No se pudo confirmar la respuesta. Reintenta sin cambiar los datos: se conservará la misma operación para evitar duplicados.' : msg);
      },
    });
  }

  protected openKardexModal(row: AdminRow): void {
    if (this.design) { this.notifications.show('El historial real no está disponible en la vista de diseño.', 'info'); return; }
    this.kardexSub?.unsubscribe();
    if (this.selectedKardexRow()?.id !== row.id) this.kardexPage.set(0);
    this.selectedKardexRow.set(row);
    this.showKardexModal.set(true);
    this.kardexLoading.set(true);
    this.kardexError.set(null);
    this.kardexItems.set([]);

    const raw: InventarioItem | undefined = row.rawData;
    const existenciaId = raw?.existenciaId || row.id;

    this.kardexSub = this.adminService.getKardex(existenciaId, this.kardexPage()).pipe(
      takeUntilDestroyed(this.destroyRef),
      finalize(() => this.kardexLoading.set(false))
    ).subscribe({
      next: res => { this.kardexItems.set(res.content); this.kardexPages.set(res.totalPages); },
      error: err => {
        const msg = err?.error?.message || err?.message || 'Error al obtener el historial de movimientos.';
        this.kardexError.set(msg);
      },
    });
  }

  protected closeKardexModal(): void {
    this.kardexSub?.unsubscribe();
    this.kardexPage.set(0);
    this.showKardexModal.set(false);
    this.selectedKardexRow.set(null);
    this.kardexItems.set([]);
    this.kardexError.set(null);
  }

  protected changeKardexPage(delta: number): void {
    const row = this.selectedKardexRow();
    if (!row) return;
    this.kardexPage.update(p => p + delta);
    this.openKardexModal(row);
  }

  @HostListener('window:beforeunload', ['$event'])
  protected warnPendingStock(event: BeforeUnloadEvent): void {
    if (this.pricingAdmin?.hasPendingChanges() || this.isSavingStock() || this.stockUncertain() || this.shippingAdmin?.hasPendingChanges() || this.operationsAdmin?.hasPendingChanges() || this.technicalFieldManager?.hasPendingChanges()) { event.preventDefault(); event.returnValue=''; }
  }

  private stockPendingKey(): string | null {
    const id=this.auth.user()?.id;
    return id ? `rya.inventory.pending.${id}` : null;
  }

  private clearStockAttempt(): void {
    const key=this.stockPendingKey();
    if (key) { try { sessionStorage.removeItem(key); } catch { /* The confirmed response is still authoritative. */ } }
  }

  private restoreStockAttempt(): boolean {
    const key=this.stockPendingKey();
    if (!key) return false;
    try {
      const raw=sessionStorage.getItem(key);
      if (!raw) return false;
      if (raw.length > 16000) throw new Error('Invalid pending operation');
      const saved=JSON.parse(raw), p=saved.payload, current=saved.current;
      if (!p || !current || typeof p.operacionId!=='string' || typeof p.productoId!=='string' || typeof p.almacenId!=='string'
        || !Number.isInteger(p.versionEsperada) || !Number.isInteger(p.cantidad) || typeof p.nota!=='string'
        || !['ENTRADA','SALIDA','AJUSTE'].includes(p.tipo)) throw new Error('Invalid pending operation');
      this.stockAttempt=p;this.stockOperation=p.operacionId;
      this.stockForm.set({productoId:p.productoId,almacenId:p.almacenId,tipo:p.tipo,cantidad:p.cantidad,nota:p.nota});
      this.stockCurrent.set(current);this.stockUncertain.set(true);this.selectedStockRow.set(null);
      this.stockError.set('Se recuperó una operación pendiente de esta pestaña. Reintenta para consultar su resultado sin duplicarla.');
      this.showStockModal.set(true);
      return true;
    } catch {
      this.notifications.show('No se pudo recuperar la operación pendiente. Revisa el historial antes de realizar otro movimiento.', 'error');
      return true;
    }
  }
}
