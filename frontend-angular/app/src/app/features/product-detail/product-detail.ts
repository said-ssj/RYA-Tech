import { CustomerFeedback } from '../../shared/customer-feedback/customer-feedback';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { BehaviorSubject, catchError, combineLatest, forkJoin, map, of, switchMap } from 'rxjs';
import { LoadingState } from '../../shared/loading-state/loading-state';
import { RequestError } from '../../shared/request-error/request-error';
import { DialogFocus } from '../../shared/dialog-focus/dialog-focus';
import { CartService } from '../../core/cart/cart.service';
import { CatalogService } from '../catalog/catalog.service';
import { ProductSummary } from '../catalog/catalog.models';
import { ProductDetail, ProductReviews } from './product-detail.models';
import { ProductDetailService } from './product-detail.service';
import { FavoriteButton } from '../../shared/favorite-button/favorite-button';
import { productSpecifications, specificationSource, usesComponentConfigurator } from './product-specifications';

type ViewState = 'loading' | 'ready' | 'error';

@Component({
  selector: 'app-product-detail',
  imports: [CustomerFeedback, CurrencyPipe, DatePipe, RouterLink, FavoriteButton, LoadingState, RequestError, DialogFocus],
  templateUrl: './product-detail.html',
  styleUrl: './product-detail.scss',
})
export class ProductDetailComponent implements OnInit {
  private readonly refresh = new BehaviorSubject(0);
  protected readonly notFound = signal(false);
  protected retry(): void { this.refresh.next(this.refresh.value + 1); }
  private readonly route = inject(ActivatedRoute);
  private readonly detailService = inject(ProductDetailService);
  private readonly catalog = inject(CatalogService);
  private readonly cart = inject(CartService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly state = signal<ViewState>('loading');
  protected readonly product = signal<ProductDetail | null>(null);
  protected readonly specifications = computed(() => productSpecifications(this.product()?.attributes??[]));
  protected readonly hasCommercialSpecs = computed(() => this.specifications().some(attribute=>attribute.pendingReview));
  protected readonly specSource = computed(() => specificationSource(this.product()?.attributes??[]));
  protected readonly usesConfigurator = computed(() => usesComponentConfigurator(this.product()?.attributes??[]));
  protected readonly reviews = signal<ProductReviews>({ average: 0, total: 0, items: [] });
  protected readonly reviewsError = signal(false);
  protected readonly relatedError = signal(false);
  protected readonly related = signal<ProductSummary[]>([]);
  protected readonly activeImage = signal<string | null>(null);
  protected readonly zoomOpen = signal(false);
  protected readonly quantity = signal(1);
  protected readonly adding = signal(false);
  protected readonly cartMessage = signal('');
  protected readonly formattedStock = computed(() => {
    const stock = this.product()?.availableStock ?? 0;
    return stock > 0 ? `${stock} ${stock === 1 ? 'unidad disponible' : 'unidades disponibles'}` : 'Sin stock';
  });
  protected readonly stars = [1, 2, 3, 4, 5];

  ngOnInit(): void {
    combineLatest([this.route.paramMap, this.refresh]).pipe(
      map(([params]) => params.get('slug') ?? ''),
      switchMap(slug => {
        this.state.set('loading');
        this.notFound.set(false);
        this.reviewsError.set(false); this.relatedError.set(false);
        this.cartMessage.set('');
        return this.detailService.getBySlug(slug).pipe(
          switchMap(product => forkJoin({
            product: of(product),
            reviews: this.detailService.getReviews(product.id).pipe(
              catchError(() => { this.reviewsError.set(true); return of({ average: 0, total: 0, items: [] } as ProductReviews); }),
            ),
            related: this.catalog.getProducts('', 0, 5, { __categoria: [product.categorySlug] }).pipe(
              map(page => page.items.filter(item => item.id !== product.id).slice(0, 4)),
              catchError(() => { this.relatedError.set(true); return of([] as ProductSummary[]); }),
            ),
          })),
          catchError(error => {
            this.product.set(null); this.notFound.set(error.status === 404); this.state.set('error');
            return of(null);
          }),
        );
      }),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe({
      next: result => {
        if (!result) return;
        const { product, reviews, related } = result;
        this.product.set(product);
        this.reviews.set(reviews);
        this.related.set(related);
        this.activeImage.set(product.images[0]?.url ?? null);
        this.quantity.set(1);
        this.state.set('ready');
      },
      error: () => {
        this.product.set(null);
        this.state.set('error');
      },
    });
  }

  protected changeQuantity(change: number): void {
    const max = this.product()?.availableStock ?? 0;
    this.quantity.set(Math.min(max, Math.max(1, this.quantity() + change)));
  }

  protected addToCart(): void {
    const product = this.product();
    if (!product || product.availableStock < 1 || this.adding()) return;
    this.adding.set(true);
    this.cartMessage.set('');
    this.cart.add(product.id, this.quantity()).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.adding.set(false);
        this.cartMessage.set(`${product.name} fue añadido al carrito.`);
      },
      error: error => {
        this.adding.set(false);
        this.cartMessage.set(error?.error?.message ?? 'No se pudo añadir el producto al carrito.');
      },
    });
  }

  protected attributeValue(value: string, unit: string | null): string {
    if (!unit || !value || value.toLocaleLowerCase().includes(unit.toLocaleLowerCase())) return value || 'No especificado';
    return `${value} ${unit}`;
  }
}
