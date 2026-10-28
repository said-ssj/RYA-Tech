import { Location } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { PostLoginSync } from '../../core/auth/post-login-sync.service';

type AuthMode = 'login' | 'register' | 'recover' | 'reset';

@Component({
  selector: 'app-auth-page',
  imports: [FormsModule, RouterLink],
  templateUrl: './auth-page.html',
  styleUrl: './auth-page.scss',
})
export class AuthPage implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly auth = inject(AuthService);
  private readonly postLogin = inject(PostLoginSync);

  protected mode: AuthMode = 'login';
  protected name = '';
  protected phone = '';
  protected email = '';
  protected password = '';
  protected confirmPassword = '';
  protected busy = false;
  protected message = '';
  protected success = false;
  protected resetReady = false;
  protected resetComplete = false;

  ngOnInit(): void {
    this.mode = this.route.snapshot.data['mode'] as AuthMode;
    if (this.route.snapshot.queryParamMap.get('confirmed')) {
      this.success = true;
      this.message = 'Correo confirmado. Ya puedes iniciar sesión.';
    }
    if (this.mode === 'reset') {
      this.busy=true;
      void this.auth.initialize().then(session => {
        this.resetReady=!!session && this.auth.recoveryPending();
        if (!this.resetReady) this.message = 'El enlace de recuperación no es válido o ya expiró. Solicita uno nuevo.';
      }).catch(()=>{this.message='No se pudo verificar el enlace. Inténtalo nuevamente o solicita otro.';})
        .finally(()=>{this.busy=false;});
    }
  }

  protected async submit(): Promise<void> {
    if (this.busy || (this.mode==='reset' && (!this.resetReady || this.resetComplete))) return;
    this.message = ''; this.success = false;
    if (!this.valid()) return;
    this.busy = true;
    try {
      if (this.mode === 'login') {
        await this.auth.signIn(this.email, this.password);
        await this.continueAfterAuth();
      } else if (this.mode === 'register') {
        const sessionCreated = await this.auth.signUp(this.name, this.phone, this.email, this.password);
        if (sessionCreated) {
          await this.continueAfterAuth();
        } else {
          this.success = true;
          this.message = 'Cuenta creada. Revisa tu correo para confirmar el acceso.';
        }
      } else if (this.mode === 'recover') {
        await this.auth.requestPasswordReset(this.email);
        this.success = true;
        this.message = 'Si el correo existe, recibirás un enlace para restablecer la contraseña.';
      } else {
        await this.auth.updatePassword(this.password);
        this.success = true;
        this.message = 'Contraseña actualizada. Ya puedes continuar con tu cuenta.';
        this.resetComplete=true;this.resetReady=false;
        this.password = ''; this.confirmPassword = '';
      }
    } catch (error: any) {
      this.message = this.friendlyError(error?.message);
    } finally {
      this.busy = false;
    }
  }

  protected title(): string {
    return { login: 'Inicia sesión', register: 'Crea tu cuenta', recover: 'Recupera tu acceso', reset: 'Define una nueva contraseña' }[this.mode];
  }

  private async continueAfterAuth(): Promise<void> {
    this.postLogin.start();
    if (!await this.router.navigateByUrl(this.returnUrl())) {
      this.success = true;
      this.message = 'La sesión está iniciada, pero se canceló la navegación. Puedes continuar desde el menú de tu cuenta.';
    }
  }

  protected subtitle(): string {
    return {
      login: 'Accede a tus favoritos, compras y configuraciones.',
      register: 'Tu identidad se gestiona de forma segura con Supabase Auth.',
      recover: 'Te enviaremos un enlace seguro al correo registrado.',
      reset: 'Usa una contraseña nueva de al menos 8 caracteres.',
    }[this.mode];
  }

  protected goBack(): void {
    if (window.history.length > 1) {
      this.location.back();
      return;
    }

    void this.router.navigate(['/']);
  }

  protected authQueryParams(): { returnUrl: string } | null {
    const returnUrl = this.requestedReturnUrl();
    return returnUrl ? { returnUrl } : null;
  }

  private valid(): boolean {
    if ((this.mode === 'login' || this.mode === 'register' || this.mode === 'recover')
        && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(this.email)) {
      this.message = 'Ingresa un correo electrónico válido.'; return false;
    }
    if (this.mode === 'register' && this.name.trim().length < 3) {
      this.message = 'Ingresa tu nombre completo.'; return false;
    }
    if (this.mode !== 'recover' && this.password.length < 8) {
      this.message = 'La contraseña debe tener al menos 8 caracteres.'; return false;
    }
    if ((this.mode === 'register' || this.mode === 'reset') && this.password !== this.confirmPassword) {
      this.message = 'Las contraseñas no coinciden.'; return false;
    }
    return true;
  }

  private returnUrl(): string {
    return this.requestedReturnUrl() ?? '/cuenta';
  }

  private requestedReturnUrl(): string | null {
    const value = this.route.snapshot.queryParamMap.get('returnUrl');
    if (!value?.startsWith('/')) return null;
    try {
      const decoded = decodeURIComponent(value);
      if (decoded.startsWith('//') || /[\\\u0000-\u001f\u007f]/.test(decoded)) return null;
      const path = decoded.split(/[?#]/, 1)[0];
      if (['/login', '/registro', '/recuperar-clave', '/restablecer-clave'].includes(path)) return null;
      return value;
    } catch { return null; }
  }

  private friendlyError(message?: string): string {
    if (!message) return 'No se pudo completar la operación.';
    if (/invalid login credentials/i.test(message)) return 'Correo o contraseña incorrectos.';
    if (/already registered/i.test(message)) return 'Este correo ya está registrado.';
    if (/email rate limit/i.test(message)) return 'Espera unos minutos antes de solicitar otro correo.';
    if (/expired|invalid/i.test(message)) return 'El enlace o la sesión ya no son válidos.';
    return message;
  }
}
