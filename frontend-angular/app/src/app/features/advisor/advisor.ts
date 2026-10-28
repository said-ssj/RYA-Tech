import { CommonModule } from '@angular/common';
import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { firstValueFrom, Subscription } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AuthService } from '../../core/auth/auth.service';
import { AdvisorBootstrap, BuilderDraftUi, BuilderMode, BuilderValidation, ComponentOption, CpuComparison, SavedConfiguration } from './advisor.models';
import { AdvisorService } from './advisor.service';
import { DialogFocus } from '../../shared/dialog-focus/dialog-focus';
import { LoadingState } from '../../shared/loading-state/loading-state';
import { RequestError } from '../../shared/request-error/request-error';

const UI_DRAFT_KEY = 'rya.builder.ui.v1';
const EMPTY_DRAFT: BuilderDraftUi = {
  mode: 'presupuesto', familyId: '', profileId: '', needId: '', resolutionId: '', budget: null,
  activeComponentCode: null, anchorComponentCode: '', anchorProductId: '',
};

@Component({
  selector: 'app-advisor',
  imports: [CommonModule, FormsModule, DialogFocus, LoadingState, RequestError],
  templateUrl: './advisor.html',
  styleUrl: './advisor.scss',
})
export class Advisor implements OnInit {
  private readonly api = inject(AdvisorService);
  private readonly destroyRef = inject(DestroyRef);
  private optionsRequest?: Subscription;
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  protected readonly auth = inject(AuthService);

  readonly mode = signal<BuilderMode>('presupuesto');
  readonly bootstrap = signal<AdvisorBootstrap | null>(null);
  readonly status = signal<'loading' | 'ready' | 'error'>('loading');
  readonly optionsStatus = signal<'idle' | 'loading' | 'ready' | 'error'>('idle');
  readonly options = signal<ComponentOption[]>([]);
  readonly preview = signal<ComponentOption | null>(null);
  readonly previewImage = signal<string | null>(null);
  readonly zoomOpen = signal(false);
  readonly selection = signal<Record<string, ComponentOption>>({});
  readonly validation = signal<BuilderValidation | null>(null);
  readonly validationError = signal(false);
  readonly validationErrorMessage = signal('');
  readonly selectedBrand = signal<string | null>(null);
  readonly recommendationStatus = signal<'idle' | 'loading' | 'error'>('idle');
  readonly lockedCodes = signal<Set<string>>(new Set());
  readonly history = signal<Array<{ selection: Record<string, ComponentOption>; locked: string[] }>>([]);
  readonly compareItems = signal<ComponentOption[]>([]);
  readonly cpuComparison = signal<CpuComparison | null>(null);
  readonly compareMessage = signal('');
  readonly peripheralsOpen = signal(false);
  readonly savedConfigurations = signal<SavedConfiguration[]>([]);
  readonly saveStatus = signal<'idle' | 'saving' | 'saved' | 'error'>('idle');
  readonly saveMessage = signal('');
  configurationName = '';
  draft: BuilderDraftUi = this.restoreDraft();

  ngOnInit(): void {
    const requestedMode = this.route.snapshot.queryParamMap.get('modo');
    this.mode.set(this.isMode(requestedMode) ? requestedMode : this.draft.mode);
    this.draft.mode = this.mode();
    this.persistDraft();
    this.loadBootstrap();
    void this.auth.initialize().then(session => { if (session) this.loadSavedConfigurations(); });
  }

  selectMode(mode: BuilderMode): void {
    this.mode.set(mode);
    this.draft.mode = mode;
    this.persistDraft();
    void this.router.navigate([], { relativeTo: this.route, queryParams: { modo: mode }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  toggleComponentType(code: string): void {
    if (this.draft.activeComponentCode !== code) { this.selectComponentType(code); return; }
    this.optionsRequest?.unsubscribe();
    this.draft.activeComponentCode = null;
    this.options.set([]);
    this.optionsStatus.set('idle');
    this.persistDraft();
  }

  selectComponentType(code: string): void {
    this.optionsRequest?.unsubscribe();
    this.draft.activeComponentCode = code;
    if (this.peripheralTypes().some(type=>type.code===code)) this.peripheralsOpen.set(true);
    this.options.set([]);
    this.selectedBrand.set(null);
    this.optionsStatus.set('loading');
    this.persistDraft();
    this.optionsRequest = this.api.getCompatibleOptions(code, Object.values(this.selection()).map(item => item.id)).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: options => { this.options.set(options); this.optionsStatus.set('ready'); },
      error: () => this.optionsStatus.set('error'),
    });
  }

  engineLoading(): boolean { return this.status() === 'loading' || this.optionsStatus() === 'loading' || this.recommendationStatus() === 'loading'; }
  engineStatus(): string {
    if (this.engineLoading()) return 'Cargando...';
    if (this.status() === 'error' || this.optionsStatus() === 'error') return 'No se pudo cargar';
    return this.draft.activeComponentCode && this.optionsStatus() === 'ready' && this.options().length ? 'Piezas encontradas' : 'Motor listo';
  }
  componentIcon(code: string): string {
    const key=this.normalizeCode(code);
    if (/CPU|PROCESADOR/.test(key)) return 'memory';
    if (/PLACA|MOTHERBOARD|BOARD|^MB$/.test(key)) return 'developer_board';
    if (/RAM|MEMORIA/.test(key)) return 'memory_alt';
    if (/ALMACEN|STORAGE|SSD|HDD/.test(key)) return 'hard_drive';
    if (/GPU|GRAFICA/.test(key)) return 'developer_board';
    if (/FUENTE|PSU/.test(key)) return 'power';
    if (/GABINETE|CASE/.test(key)) return 'desktop_windows';
    if (/COOLER|REFRIGER/.test(key)) return 'mode_fan';
    if (/TECLADO|KEYBOARD/.test(key)) return 'keyboard';
    if (/MOUSEPAD/.test(key)) return 'rectangle';
    if (/MOUSE/.test(key)) return 'mouse';
    if (/MONITOR/.test(key)) return 'monitor';
    if (/MICROFONO/.test(key)) return 'mic';
    if (/CAMARA|WEBCAM/.test(key)) return 'videocam';
    if (/AUDIFONO|HEADSET|HEADPHONE/.test(key)||key==='AUD') return 'headphones';
    return this.bootstrap()?.componentTypes.find(type=>type.code===code)?.icon || 'devices';
  }

  filteredOptions(): ComponentOption[] {
    const brand = this.selectedBrand();
    return brand ? this.options().filter(option => option.brand === brand) : this.options();
  }

  brands(): string[] {
    return [...new Set(this.options().map(option => option.brand).filter((brand): brand is string => Boolean(brand)))];
  }

  onDraftChange(): void { this.persistDraft(); }

  profiles() {
    return this.bootstrap()?.profileFamilies.find(item => item.id === this.draft.familyId)?.profiles ?? [];
  }

  needs() {
    return this.profiles().find(profile => profile.id === this.draft.profileId)?.needs ?? [];
  }

  selectOption(option: ComponentOption): void {
    const code = this.draft.activeComponentCode;
    if (!code) return;
    if (this.isLocked(code)) {
      this.validationError.set(true);
      return;
    }
    this.rememberState();
    this.showPreview(option);
    this.selection.update(current => ({ ...current, [code]: option }));
    this.validationError.set(false);
    this.validateCurrentSelection();
  }

  showPreview(option: ComponentOption): void {
    this.preview.set(option);
    this.previewImage.set(option.imageUrls?.[0] ?? option.imageUrl);
  }

  previewImages(): string[] {
    const item = this.preview();
    if (!item) return [];
    return item.imageUrls?.length ? item.imageUrls : item.imageUrl ? [item.imageUrl] : [];
  }

  removeSelection(code: string): void {
    if (this.isLocked(code)) return;
    this.rememberState();
    const next = { ...this.selection() };
    delete next[code];
    this.selection.set(next);
    if (this.draft.activeComponentCode === code) { this.preview.set(null); this.previewImage.set(null); }
    this.validateCurrentSelection();
  }

  toggleLock(code: string): void {
    if (!this.selectedFor(code)) return;
    this.rememberState();
    const next = new Set(this.lockedCodes());
    next.has(code) ? next.delete(code) : next.add(code);
    this.lockedCodes.set(next);
    this.validateCurrentSelection();
  }

  isLocked(code: string): boolean { return this.lockedCodes().has(code); }

  undo(): void {
    const states = [...this.history()];
    const previous = states.pop();
    if (!previous) return;
    this.history.set(states);
    this.selection.set({ ...previous.selection });
    this.lockedCodes.set(new Set(previous.locked));
    const first = Object.values(previous.selection)[0] ?? null;
    this.preview.set(first);
    this.previewImage.set(first?.imageUrl ?? null);
    this.validateCurrentSelection();
  }

  toggleCompare(option: ComponentOption): void {
    const current = this.compareItems();
    if (current.some(item => item.id === option.id)) {
      this.compareItems.set(current.filter(item => item.id !== option.id));
      this.cpuComparison.set(null);
      this.compareMessage.set('');
      return;
    }
    if (current.length >= 3) {
      this.compareMessage.set('Puedes comparar hasta tres productos simultáneamente.');
      return;
    }
    const next = [...current, option];
    this.compareItems.set(next);
    this.compareMessage.set('');
    if (this.isCpuType(option.componentTypeCode) && next.every(item => this.isCpuType(item.componentTypeCode))) {
      this.api.compareCpus(next.map(item => item.id)).subscribe({
        next: result => this.cpuComparison.set(result),
        error: () => this.cpuComparison.set(null),
      });
    }
  }

  isCompared(id: string): boolean { return this.compareItems().some(item => item.id === id); }

  clearComparison(): void { this.compareItems.set([]); this.cpuComparison.set(null); this.compareMessage.set(''); }

  comparisonPriceDifference(option: ComponentOption): number {
    const reference = this.compareItems()[0]?.price ?? option.price;
    return option.price - reference;
  }

  private isPeripheral(type: {code:string;name:string}): boolean {
    const value=this.normalizeCode(`${type.code} ${type.name}`);
    return ['PERIF','TECL','KEYBOARD','MOUSE','MONITOR','AUDIO','AUDIF','AURIC','HEADPHONE','HEADSET','WEBCAM','CAMARA','MICROF'].some(token=>value.includes(token));
  }
  mainComponentTypes() { return (this.bootstrap()?.componentTypes ?? []).filter(type=>!this.isPeripheral(type)); }
  peripheralTypes() { return (this.bootstrap()?.componentTypes ?? []).filter(type=>this.isPeripheral(type)); }

  powerUsagePercent(): number {
    const draw = this.validation()?.powerDrawWatts ?? 0;
    const recommended = this.validation()?.recommendedPsuWatts ?? 0;
    return recommended > 0 ? Math.min(Math.round((draw / recommended) * 100), 100) : 0;
  }

  async saveConfiguration(): Promise<void> {
    if (!this.auth.isAuthenticated()) {
      await this.router.navigate(['/login'], { queryParams: { returnUrl: this.router.url } });
      return;
    }
    const components = Object.entries(this.selection());
    const budget = this.draft.budget ?? this.validation()?.total ?? 0;
    if (!components.length || budget <= 0) {
      this.saveStatus.set('error');
      this.saveMessage.set('Selecciona componentes y define un presupuesto antes de guardar.');
      return;
    }
    if (!this.validation()?.compatible) {
      this.saveStatus.set('error');
      this.saveMessage.set('La configuración debe superar la validación de compatibilidad antes de guardarse.');
      return;
    }
    this.saveStatus.set('saving');
    this.saveMessage.set('');
    try {
      const created = await firstValueFrom(this.api.createConfiguration(this.configurationName || 'Mi configuración RYA', budget));
      for (const [code, option] of components) {
        await firstValueFrom(this.api.applyConfigurationChange(created.id, code, option, this.isLocked(code)));
      }
      this.saveStatus.set('saved');
      this.saveMessage.set('Configuración guardada y revalidada con precios y stock vigentes.');
      this.configurationName = '';
      this.loadSavedConfigurations();
    } catch (error: any) {
      this.saveStatus.set('error');
      this.saveMessage.set(error?.error?.message ?? 'No se pudo guardar la configuración.');
    }
  }

  recoverConfiguration(configuration: SavedConfiguration): void {
    this.rememberState();
    const restored = Object.fromEntries(configuration.componentes.map(item => [item.tipoCodigo, {
      id: item.productoId,
      componentTypeCode: item.tipoCodigo,
      name: item.productoNombre,
      brand: null,
      slug: item.sku,
      summary: item.sku,
      price: item.precioSnapshot,
      currency: 'PEN' as const,
      stock: item.stockDisponible,
      imageUrl: null,
      imageUrls: [],
      compatibility: 'CONDITIONAL' as const,
    }]));
    this.selection.set(restored);
    this.lockedCodes.set(new Set(configuration.componentes.filter(item => item.bloqueado).map(item => item.tipoCodigo)));
    this.draft.budget = configuration.presupuesto;
    this.persistDraft();
    const first = Object.values(restored)[0] ?? null;
    this.preview.set(first);
    this.previewImage.set(first?.imageUrl ?? null);
    this.validateCurrentSelection();
    this.saveMessage.set(`Configuración “${configuration.nombre ?? 'Sin nombre'}” recuperada.`);
    this.saveStatus.set('saved');
  }

  selectedFor(code: string): ComponentOption | null { return this.selection()[code] ?? null; }

  activeTypeName(): string {
    return this.bootstrap()?.componentTypes.find(type => type.code === this.draft.activeComponentCode)?.name ?? 'Componente';
  }

  modeTitle(): string {
    if (this.mode() === 'manual') return 'Armado manual guiado';
    if (this.mode() === 'componente') return 'Construir alrededor de un componente';
    return 'Armado automático por perfil y presupuesto';
  }

  runRecommendation(anchor = false): void {
    this.recommendationStatus.set('loading');
    this.api.recommend({
      mode: this.mode(), budget: this.draft.budget, profileId: this.draft.profileId,
      needId: this.draft.needId || null, resolutionId: this.draft.resolutionId || null,
      anchorProductId: anchor ? this.draft.anchorProductId : null,
    }).subscribe({
      next: result => {
        this.rememberState();
        this.validation.set(result);
        this.selection.set(Object.fromEntries(result.components.map(component => [component.componentTypeCode, component])));
        if (result.components.length) this.showPreview(result.components[0]);
        this.recommendationStatus.set('idle');
      },
      error: () => this.recommendationStatus.set('error'),
    });
  }

  resultDeficit(): number {
    return Math.max((this.validation()?.total ?? 0) - (this.draft.budget ?? 0), 0);
  }

  retry(): void { this.loadBootstrap(); }

  private validateCurrentSelection(): void {
    const selection = this.selection();
    if (this.draft.activeComponentCode) this.selectComponentType(this.draft.activeComponentCode);
    if (!Object.keys(selection).length) {
      this.validation.set(null);
      return;
    }
    const lockedIds = [...this.lockedCodes()].map(code => selection[code]?.id).filter((id): id is string => Boolean(id));
    this.api.validateSelection(selection, lockedIds).subscribe({
      next: result => { this.validation.set(result); this.validationError.set(false); },
      error: (error: any) => { this.validation.set(null); this.validationError.set(true); this.validationErrorMessage.set(error?.error?.message ?? error?.message ?? 'Error desconocido'); },
    });
  }

  private rememberState(): void {
    const snapshot = { selection: { ...this.selection() }, locked: [...this.lockedCodes()] };
    this.history.update(items => [...items.slice(-19), snapshot]);
  }

  private loadSavedConfigurations(): void {
    this.api.listConfigurations().subscribe({
      next: items => this.savedConfigurations.set(items),
      error: () => this.savedConfigurations.set([]),
    });
  }

  private isCpuType(code: string): boolean {
    const normalized = this.normalizeCode(code);
    return normalized === 'CPU' || normalized.includes('PROCESADOR');
  }

  private loadBootstrap(): void {
    this.status.set('loading');
    this.api.getBootstrap().subscribe({
      next: data => { this.bootstrap.set(data); this.status.set('ready');
        if (this.draft.activeComponentCode && data.componentTypes.some(type => type.code === this.draft.activeComponentCode)) this.selectComponentType(this.draft.activeComponentCode);
        else this.draft.activeComponentCode = null;
      },
      error: () => { this.bootstrap.set(null); this.status.set('error'); },
    });
  }

  private restoreDraft(): BuilderDraftUi {
    if (typeof localStorage === 'undefined') return { ...EMPTY_DRAFT };
    try {
      const stored = localStorage.getItem(UI_DRAFT_KEY);
      if (!stored) return { ...EMPTY_DRAFT };
      const parsed = JSON.parse(stored) as Partial<BuilderDraftUi>;
      return { ...EMPTY_DRAFT, ...parsed, mode: this.isMode(parsed.mode) ? parsed.mode : 'presupuesto' };
    } catch { return { ...EMPTY_DRAFT }; }
  }

  private persistDraft(): void {
    if (typeof localStorage !== 'undefined') localStorage.setItem(UI_DRAFT_KEY, JSON.stringify(this.draft));
  }

  private isMode(value: unknown): value is BuilderMode {
    return value === 'presupuesto' || value === 'manual' || value === 'componente';
  }

  private normalizeCode(code: string): string {
    return code.normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-zA-Z0-9]/g, '').toUpperCase();
  }
}
