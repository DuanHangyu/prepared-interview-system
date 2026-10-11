import {copyUploadAnswer,fixedUploadIntent,uploadCheck,uploadHash,UploadError,type UploadAnswer,type UploadIntent,type UploadOwner} from './uploadBundle';
type Row<T>={key:string;namespace:string;answerKey?:string;order?:string;value:T;sha256:string};
/** Immutable transport intents only. Never stores tokens, audio copies, deletions or a media-SAVED flag. */
export class PreparedUploadJournal {
  private namespace:string;private connection?:Promise<IDBDatabase>;private db?:IDBDatabase;private closed=false;
  private owner:UploadOwner;
  constructor(owner:UploadOwner,private readonly assessmentId:number,private readonly factory:IDBFactory=globalThis.indexedDB){
    uploadCheck(!!owner);this.owner={...owner};
    uploadCheck(Number.isSafeInteger(owner.studentId)&&owner.studentId>0&&owner.studentId<=2147483647&&Number.isSafeInteger(assessmentId)&&assessmentId>0&&assessmentId<=2147483647);
    uploadCheck(/^prepared-[a-z0-9][a-z0-9-]{0,70}$/.test(owner.deploymentId));
    try{const u=new URL(owner.origin);uploadCheck(u.origin===owner.origin&&!u.username&&!u.password&&(u.protocol==='https:'||(u.protocol==='http:'&&['localhost','127.0.0.1','[::1]'].includes(u.hostname))));}catch{throw new UploadError('INVALID');}
    uploadCheck(!!factory&&typeof factory.open==='function','UNAVAILABLE');this.namespace=JSON.stringify([1,owner.deploymentId,owner.origin,owner.studentId,assessmentId]);
  }
  private live(){uploadCheck(!this.closed,'CLOSED');}
  private answerKey(a:UploadAnswer){this.live();const fixed=copyUploadAnswer(a);uploadCheck(fixed.assessmentId===this.assessmentId);return JSON.stringify([this.namespace,fixed.attemptId,fixed.answerId]);}
  private async database(){
    this.live();this.connection??=new Promise((resolve,reject)=>{
      let r:IDBOpenDBRequest,failed=false;try{r=this.factory.open('prepared-interview-upload-v1',1);}catch{reject(new UploadError('UNAVAILABLE'));return;}
      r.onupgradeneeded=()=>{const answers=r.result.createObjectStore('answers',{keyPath:'key'});answers.createIndex('owner','namespace');answers.createIndex('order','order',{unique:true});
        const intents=r.result.createObjectStore('intents',{keyPath:'key'});intents.createIndex('answer','answerKey');r.result.createObjectStore('claims',{keyPath:'key'});};
      r.onerror=r.onblocked=()=>{failed=true;reject(new UploadError('UNAVAILABLE'));};
      r.onsuccess=()=>{const db=r.result;if(failed||this.closed){db.close();reject(new UploadError('CLOSED'));return;}this.db=db;db.onversionchange=()=>this.close();resolve(db);};
    });try{const db=await this.connection;this.live();return db;}catch(e){this.connection=undefined;throw e;}
  }
  private async transaction<T>(mode:IDBTransactionMode,body:(tx:IDBTransaction,result:(v:T)=>void,fail:(code:'CONFLICT'|'MISSING_ORIGINAL'|'INTEGRITY')=>void)=>void):Promise<T>{
    const db=await this.database();this.live();return new Promise((resolve,reject)=>{
      let tx:IDBTransaction;try{tx=db.transaction(['answers','intents','claims'],mode,mode==='readwrite'?{durability:'strict'}:undefined);}catch{reject(new UploadError('UNAVAILABLE'));return;}
      let value:T,failure:UploadError|undefined;tx.onabort=()=>reject(failure??new UploadError('WRITE_FAILED'));tx.oncomplete=()=>{try{this.live();resolve(value);}catch(e){reject(e);}};
      const fail=(code:'CONFLICT'|'MISSING_ORIGINAL'|'INTEGRITY')=>{failure??=new UploadError(code);try{tx.abort();}catch{/* Original transaction already ended. */}};
      try{body(tx,v=>{value=v;},fail);}catch{fail('INTEGRITY');}
    });
  }
  private async checkRow<T>(row:Row<T>,key:string):Promise<T>{
    uploadCheck(!!row&&row.key===key&&row.namespace===this.namespace&&row.sha256===await uploadHash(JSON.stringify([row.key,row.value])),'INTEGRITY');this.live();return row.value;
  }
  async writerKey(){this.live();const hash=await uploadHash(JSON.stringify(['prepared-upload-v1',this.namespace]));this.live();return 'prepared-capture-v1:'+hash;}
  async track(input:UploadAnswer):Promise<UploadAnswer>{
    const value=copyUploadAnswer(input),key=this.answerKey(value),order=JSON.stringify([this.namespace,value.attemptId,value.ordinal]);
    const row:Row<UploadAnswer>={key,namespace:this.namespace,order,value,sha256:await uploadHash(JSON.stringify([key,value]))};
    const result=await this.transaction<Row<UploadAnswer>>('readwrite',(tx,result,fail)=>{const s=tx.objectStore('answers'),r=s.get(key);r.onsuccess=()=>{
      if(r.result){if(JSON.stringify(r.result)!==JSON.stringify(row)){fail('CONFLICT');return;}result(r.result);return;}
      const other=s.index('order').get(order);other.onsuccess=()=>{if(other.result){fail('CONFLICT');return;}s.add(row);result(row);};};});
    return copyUploadAnswer(await this.checkRow(result,key));
  }
  /** Transport callers must supply their server-verified original attempt.
   * Reuse the v1 unique order index: no migration, history scan or data deletion. */
  async answers(attemptId:string):Promise<UploadAnswer[]>{
    this.live();
    uploadCheck(typeof attemptId==='string'&&/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(attemptId));
    const prefix=JSON.stringify([this.namespace,attemptId]).slice(0,-1)+',';
    const rows=await this.transaction<Row<UploadAnswer>[]>('readonly',(tx,result)=>{
      const r=tx.objectStore('answers').index('order').getAll(IDBKeyRange.bound(prefix,prefix+'\uffff'),51);
      r.onsuccess=()=>result(r.result);
    });
    uploadCheck(rows.length<=50,'INTEGRITY');const values=[],orders=new Set<string>();
    for(const row of rows){const a=copyUploadAnswer(await this.checkRow(row,row.key)),order=JSON.stringify([this.namespace,a.attemptId,a.ordinal]);
      uploadCheck(row.key===this.answerKey(a)&&row.order===order&&a.attemptId===attemptId&&!orders.has(order),'INTEGRITY');
      orders.add(order);values.push(a);
    }return values.sort((a,b)=>a.ordinal-b.ordinal);
  }
  async intents(answer:UploadAnswer):Promise<UploadIntent[]>{
    const fixed=copyUploadAnswer(answer),answerKey=this.answerKey(fixed);
    const rows=await this.transaction<Row<UploadIntent>[]>('readonly',(tx,result)=>{const r=tx.objectStore('intents').index('answer').getAll(answerKey,10001);r.onsuccess=()=>result(r.result);});
    uploadCheck(rows.length<=10000,'INTEGRITY');const values=[];
    for(const row of rows){const v=await this.checkRow(row,row.key),rebuilt=await fixedUploadIntent(this.owner.studentId,v.answer,v.command);
      uploadCheck(row.answerKey===answerKey&&row.key===JSON.stringify([answerKey,v.bundleId])&&JSON.stringify(v)===JSON.stringify(rebuilt)&&JSON.stringify(v.answer)===JSON.stringify(fixed),'INTEGRITY');this.live();values.push(rebuilt);}
    return values.sort((a,b)=>a.command.segmentOrdinal-b.command.segmentOrdinal||a.command.chunks[0].index-b.command.chunks[0].index);
  }
  async reserve(input:UploadIntent):Promise<UploadIntent>{
    // Recompute before opening any transaction. Claims and header then commit atomically before HTTP.
    const value=await fixedUploadIntent(this.owner.studentId,input.answer,input.command);uploadCheck(JSON.stringify(value)===JSON.stringify(input),'INTEGRITY');
    const answerKey=this.answerKey(value.answer),key=JSON.stringify([answerKey,value.bundleId]);
    const witness=await this.transaction<Row<UploadAnswer>|undefined>('readonly',(tx,result)=>{const r=tx.objectStore('answers').get(answerKey);r.onsuccess=()=>result(r.result);});
    uploadCheck(!!witness,'MISSING_ORIGINAL');await this.checkRow(witness!,answerKey);
    uploadCheck(witness!.order===JSON.stringify([this.namespace,value.answer.attemptId,value.answer.ordinal]),'INTEGRITY');
    const row:Row<UploadIntent>={key,namespace:this.namespace,answerKey,value,sha256:await uploadHash(JSON.stringify([key,value]))};
    const saved=await this.transaction<Row<UploadIntent>>('readwrite',(tx,result,fail)=>{
      const answers=tx.objectStore('answers'),intents=tx.objectStore('intents'),claims=tx.objectStore('claims'),r=answers.get(answerKey);
      r.onsuccess=()=>{if(!r.result){fail('MISSING_ORIGINAL');return;}if(JSON.stringify(r.result)!==JSON.stringify(witness)){fail('INTEGRITY');return;}if(JSON.stringify(r.result.value)!==JSON.stringify(value.answer)){fail('CONFLICT');return;}
        const header=intents.get(key);header.onsuccess=()=>{if(header.result&&JSON.stringify(header.result)!==JSON.stringify(row)){fail('INTEGRITY');return;}
          const next=(position:number)=>{if(position===value.command.chunks.length){if(!header.result)intents.add(row);result(header.result??row);return;}
            const c=value.command.chunks[position],claim={key:JSON.stringify([answerKey,value.command.segmentId,c.index]),bundleId:value.bundleId},req=claims.get(claim.key);
            req.onsuccess=()=>{if(req.result&&JSON.stringify(req.result)!==JSON.stringify(claim)){fail('CONFLICT');return;}if(!req.result)claims.add(claim);next(position+1);};};next(0);};};
    });return await this.checkRow(saved,key);
  }
  close(){this.closed=true;this.db?.close();this.db=undefined;this.connection=undefined;}
}
