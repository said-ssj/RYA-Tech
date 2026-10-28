import {Component,DestroyRef,OnInit,inject,input,signal} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {Router,RouterLink} from '@angular/router';
import {HttpClient} from '@angular/common/http';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {AuthService} from '../../core/auth/auth.service';
@Component({selector:'app-customer-feedback',imports:[FormsModule,RouterLink],templateUrl:'./customer-feedback.html',styleUrl:'./customer-feedback.scss'})
export class CustomerFeedback implements OnInit {
 readonly productId=input<string|null>(null);
 protected auth=inject(AuthService);private http=inject(HttpClient);private destroy=inject(DestroyRef);private router=inject(Router);
 protected busy=signal(false);protected message=signal('');protected sent=signal(false);protected tickets=signal<any[]>([]);protected loading=signal(false);protected listError=signal(false);
 protected rating=0;protected hoverRating=0;protected subject='';protected text='';
 protected returnUrl(){return this.router.url;}
 ngOnInit(){void this.auth.initialize().then(()=>{if(this.auth.isAuthenticated()&&!this.productId())this.load();}).catch(()=>undefined);}
 protected load(){if(!this.auth.isAuthenticated())return;this.loading.set(true);this.listError.set(false);this.http.get<any[]>('/api/v1/ayuda/consultas').pipe(takeUntilDestroyed(this.destroy)).subscribe({next:r=>{this.tickets.set(r);this.loading.set(false);},error:()=>{this.loading.set(false);this.listError.set(true);}});}
 protected submit(){
  if(this.busy()||this.sent()||!this.auth.isAuthenticated())return;
  if(this.productId() && (!Number.isInteger(this.rating)||this.rating<1||this.rating>5)){this.message.set('Selecciona de 1 a 5 estrellas antes de enviar tu reseña.');return;}
  if(this.text.trim().length<10||this.text.trim().length>(this.productId()?2000:5000)||(!this.productId()&&this.subject.trim().length<5)){this.message.set('Completa el asunto y escribe al menos 10 caracteres en tu mensaje.');return;}
  this.busy.set(true);this.message.set('');
  const url=this.productId()?'/api/v1/productos/'+encodeURIComponent(this.productId()!)+'/resenas':'/api/v1/ayuda/consultas';
  const body=this.productId()?{calificacion:Number(this.rating),comentario:this.text.trim()}:{asunto:this.subject.trim(),mensaje:this.text.trim()};
  this.http.post(url,body).pipe(takeUntilDestroyed(this.destroy)).subscribe({next:()=>{this.busy.set(false);this.sent.set(true);this.text='';this.subject='';this.message.set(this.productId()?'Tu reseña fue recibida y está pendiente de moderación.':'Tu pregunta fue recibida. Puedes consultar aquí las respuestas del equipo.');if(!this.productId())this.load();},error:e=>{this.busy.set(false);this.message.set(e?.error?.message??'No pudimos confirmar el envío. Revisa tus mensajes antes de volver a intentarlo.');}});
 }
}
