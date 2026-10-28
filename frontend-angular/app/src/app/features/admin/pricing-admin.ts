import {Component,DestroyRef,OnInit,inject,output,signal} from '@angular/core';
import {CommonModule} from '@angular/common';import {FormsModule} from '@angular/forms';import {HttpClient} from '@angular/common/http';import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
@Component({selector:'app-pricing-admin',imports:[CommonModule,FormsModule],templateUrl:'./pricing-admin.html',styleUrl:'./pricing-admin.scss'})
export class PricingAdmin implements OnInit {
 readonly busyChange=output<boolean>();private http=inject(HttpClient);private destroy=inject(DestroyRef);
 protected rows=signal<any[]>([]);protected busy=signal(false);protected error=signal('');protected message=signal('');protected product=signal<any|null>(null);protected editing=signal(false);
 protected sku='';protected amount:number|null=null;protected form:any={};private baseline='';
 ngOnInit(){this.load();}
 private setBusy(value:boolean){this.busy.set(value);this.busyChange.emit(value);}
 protected load(){if(this.busy())return;this.setBusy(true);this.error.set('');this.http.get<any[]>('/api/v1/admin/ofertas').pipe(takeUntilDestroyed(this.destroy)).subscribe({next:r=>{this.rows.set(r);this.setBusy(false);},error:e=>this.failed(e)});}
 private failed(e:any){this.setBusy(false);this.error.set(e?.error?.message??'No se confirmó la operación. Actualiza los datos antes de repetir.');}
 protected findProduct(){if(this.busy()||!this.sku.trim())return;this.setBusy(true);this.product.set(null);this.error.set('');this.message.set('');this.http.get<any>('/api/v1/admin/ofertas/producto',{params:{sku:this.sku.trim()}}).pipe(takeUntilDestroyed(this.destroy)).subscribe({next:r=>{this.product.set(r);this.amount=r.precio_base;this.setBusy(false);},error:e=>this.failed(e)});}
 protected price(){const p=this.product();if(!p||this.busy()||this.amount==null)return;this.setBusy(true);this.error.set('');this.http.put('/api/v1/admin/ofertas/precio',{sku:p.sku,precioAnterior:p.precio_base,precio:this.amount}).pipe(takeUntilDestroyed(this.destroy)).subscribe({next:()=>{this.product.set({...p,precio_base:this.amount});this.setBusy(false);this.message.set('Precio base actualizado. Las promociones vigentes se calculan sobre ese precio.');},error:e=>this.failed(e)});}
 private localDate(value:string){if(!value)return '';return new Intl.DateTimeFormat('sv-SE',{timeZone:'America/Lima',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).format(new Date(value)).replace(' ','T');}
 protected edit(row?:any){if(!this.canLeave())return;this.error.set('');this.message.set('');this.form=row?{...row,skuText:row.skus.join('\n'),inicio:this.localDate(row.inicio),fin:this.localDate(row.fin)}:{id:null,nombre:'',descuentoPorcentaje:10,inicio:'',fin:'',activa:true,skuText:'',version:null};this.baseline=JSON.stringify(this.form);this.editing.set(true);}
 protected close(){if(this.canLeave())this.editing.set(false);}
 protected save(){if(this.busy())return;const skus=this.form.skuText.split(/[\n,;]+/).map((x:string)=>x.trim()).filter(Boolean);if(!skus.length){this.error.set('Agrega al menos un SKU exacto.');return;}
 const body={...this.form,skus,inicio:this.form.inicio+':00-05:00',fin:this.form.fin+':00-05:00'};delete body.skuText;
 this.setBusy(true);this.error.set('');this.http.post('/api/v1/admin/ofertas',body).pipe(takeUntilDestroyed(this.destroy)).subscribe({next:()=>{this.setBusy(false);this.editing.set(false);this.message.set('Promoción guardada. Se aplicará únicamente durante su vigencia.');this.load();},error:e=>this.failed(e)});}
 hasPendingChanges(){return this.busy()||(this.editing()&&JSON.stringify(this.form)!==this.baseline)||(this.product()&&this.amount!==Number(this.product().precio_base));}
 canLeave(){return !this.busy()&&(!this.hasPendingChanges()||window.confirm('Hay cambios de precios u ofertas sin guardar. ¿Quieres descartarlos?'));}
}
