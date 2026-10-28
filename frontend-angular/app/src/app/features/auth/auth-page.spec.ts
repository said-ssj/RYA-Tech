import { Location } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { NEVER, of, throwError } from 'rxjs';
import { signal } from '@angular/core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthPage } from './auth-page';
import { AuthService } from '../../core/auth/auth.service';
import { AccountService } from '../../core/auth/account.service';
import { CartService } from '../../core/cart/cart.service';
import { NotificationService } from '../../core/notifications/notification.service';

describe('Auth form redirection without backend dependency',()=>{
  let page:any, auth:any, account:any,cart:any,notifications:any,router:Router;
  let data:{mode:string},query:Record<string,string>,user:ReturnType<typeof signal<any>>;
  beforeEach(()=>{
    vi.useFakeTimers();data={mode:'login'};query={};user=signal(null);
    auth={user,recoveryPending:signal(false),initialize:vi.fn(async()=>null),signIn:vi.fn(async()=>{user.set({id:'test'});}),signUp:vi.fn(async()=>{user.set({id:'test'});return true;}),requestPasswordReset:vi.fn(),updatePassword:vi.fn()};
    account={load:vi.fn(()=>NEVER)};cart={linkToAccount:vi.fn(()=>NEVER)};notifications={show:vi.fn()};
    TestBed.configureTestingModule({imports:[AuthPage],providers:[provideRouter([]),
      {provide:ActivatedRoute,useValue:{snapshot:{data,queryParamMap:{get:(key:string)=>query[key]??null}}}},
      {provide:AuthService,useValue:auth},{provide:AccountService,useValue:account},{provide:CartService,useValue:cart},{provide:NotificationService,useValue:notifications},
    ]});
    router=TestBed.inject(Router);vi.spyOn(router,'navigateByUrl').mockResolvedValue(true);
    page=TestBed.createComponent(AuthPage).componentInstance;
    page.email='test@example.invalid';page.password='test-password';page.confirmPassword='test-password';page.name='Test User';
  });
  afterEach(()=>{vi.clearAllTimers();vi.useRealTimers();TestBed.resetTestingModule();});
  it('shows an enabled password form only for a valid recovery session',async()=>{
    data.mode='reset';auth.recoveryPending.set(true);auth.initialize.mockResolvedValue({user:{id:'test'}});page.ngOnInit();await Promise.resolve();await Promise.resolve();await Promise.resolve();
    expect(page.resetReady).toBe(true);await page.submit();expect(auth.updatePassword).toHaveBeenCalledWith('test-password');expect(page.resetComplete).toBe(true);expect(page.password).toBe('');expect(router.navigateByUrl).not.toHaveBeenCalled();
    await page.submit();expect(auth.updatePassword).toHaveBeenCalledTimes(1);
  });
  it('blocks expired recovery instead of updating the password with an ordinary session',async()=>{
    data.mode='reset';auth.initialize.mockResolvedValue({user:{id:'test'}});page.ngOnInit();await Promise.resolve();await Promise.resolve();await Promise.resolve();await page.submit();
    expect(page.resetReady).toBe(false);expect(page.message).toContain('Solicita uno nuevo');expect(auth.updatePassword).not.toHaveBeenCalled();
  });
  it('reports failure verifying a reset link without leaving the submit button busy',async()=>{
    data.mode='reset';auth.initialize.mockRejectedValue(new Error('offline'));page.ngOnInit();await Promise.resolve();await Promise.resolve();await Promise.resolve();expect(page.busy).toBe(false);expect(page.resetReady).toBe(false);expect(page.message).toContain('verificar');
  });
  it('redirects immediately even when profile and cart never answer',async()=>{
    page.ngOnInit();await page.submit();expect(router.navigateByUrl).toHaveBeenCalledWith('/cuenta');expect(page.busy).toBe(false);expect(account.load).toHaveBeenCalledTimes(1);expect(cart.linkToAccount).toHaveBeenCalledTimes(1);
  });
  it('preserves returnUrl query and fragment on login',async()=>{
    query['returnUrl']='/catalogo?categoria=procesadores#productos';page.ngOnInit();await page.submit();expect(router.navigateByUrl).toHaveBeenCalledWith(query['returnUrl']);
  });
  it('registers and navigates to the original protected destination',async()=>{
    data.mode='register';query['returnUrl']='/compras';page.ngOnInit();await page.submit();expect(auth.signUp).toHaveBeenCalledTimes(1);expect(router.navigateByUrl).toHaveBeenCalledWith('/compras');
  });
  it('requires email confirmation without navigation when no registration session was issued',async()=>{
    data.mode='register';auth.signUp.mockImplementation(async()=>false);page.ngOnInit();await page.submit();expect(router.navigateByUrl).not.toHaveBeenCalled();expect(cart.linkToAccount).not.toHaveBeenCalled();expect(page.message).toContain('Revisa tu correo');expect(page.success).toBe(true);
  });
  it.each(['/login','/registro?returnUrl=/cuenta','/restablecer-clave','//external.invalid','/\\external.invalid','/%2fexternal.invalid','/%5cexternal.invalid','/bad%00path','/%not-valid'])('rejects looping or unsafe returnUrl %s',async value=>{
    query['returnUrl']=value;page.ngOnInit();await page.submit();expect(router.navigateByUrl).toHaveBeenCalledWith('/cuenta');
  });
  it('shows credential errors without navigating or synchronizing',async()=>{
    auth.signIn.mockRejectedValue(new Error('Invalid login credentials'));page.ngOnInit();await page.submit();expect(page.message).toContain('incorrectos');expect(router.navigateByUrl).not.toHaveBeenCalled();expect(account.load).not.toHaveBeenCalled();
  });
  it('prevents double submissions while authentication is pending',async()=>{
    let resolve:any;auth.signIn.mockImplementation(()=>new Promise(r=>{resolve=r;}));page.ngOnInit();const first=page.submit();await page.submit();expect(auth.signIn).toHaveBeenCalledTimes(1);user.set({id:'test'});resolve();await first;
  });
  it('reports a cancelled navigation without pretending authentication failed',async()=>{
    vi.mocked(router.navigateByUrl).mockResolvedValue(false);page.ngOnInit();await page.submit();expect(page.message).toContain('sesión está iniciada');expect(page.success).toBe(true);expect(page.busy).toBe(false);
  });
  it('bounds stalled synchronization and warns without signing out or retrying',async()=>{
    page.ngOnInit();await page.submit();await vi.advanceTimersByTimeAsync(15001);expect(notifications.show).toHaveBeenCalledTimes(1);expect(user()?.id).toBe('test');expect(cart.linkToAccount).toHaveBeenCalledTimes(1);
  });
  it('does not notify an old synchronization result after the user logs out',async()=>{
    page.ngOnInit();await page.submit();user.set(null);await vi.advanceTimersByTimeAsync(15001);expect(notifications.show).not.toHaveBeenCalled();
  });
  it('does not block navigation when the backend rejects profile or cart',async()=>{
    account.load.mockReturnValue(throwError(()=>new Error('Backend unavailable')));cart.linkToAccount.mockReturnValue(throwError(()=>new Error('Backend unavailable')));
    page.ngOnInit();await page.submit();expect(router.navigateByUrl).toHaveBeenCalledWith('/cuenta');expect(auth.signIn).toHaveBeenCalledTimes(1);
  });
  it('does not show a warning when synchronization succeeds',async()=>{
    account.load.mockReturnValue(of({}));cart.linkToAccount.mockReturnValue(of({}));page.ngOnInit();await page.submit();await vi.advanceTimersByTimeAsync(15001);expect(notifications.show).not.toHaveBeenCalled();
  });
  it('regresa al historial sin reabrir la ruta protegida que pidió iniciar sesión',()=>{
    query['returnUrl']='/compras';const back=vi.spyOn(TestBed.inject(Location),'back').mockImplementation(()=>{});
    vi.spyOn(window.history,'length','get').mockReturnValue(2);page.goBack();expect(back).toHaveBeenCalled();expect(router.navigateByUrl).not.toHaveBeenCalled();
  });
});
