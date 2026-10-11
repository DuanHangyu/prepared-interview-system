import type { SupplementScope,SupplementCommand } from '@/api/preparedSupplement';
import { checkSupplementScope,copySupplementCommand } from './supplementProof';
const fail=()=>new Error('原补传审批请求在本机保存或核验失败，请保留本机内容，不要重复创建审批');
/** Atomic, origin-local metadata only. Fresh server authorization is required before use.
 * Read/write transactions serialize tabs; no audio, tokens, auto-send or replacement-key recovery. */
export class PreparedSupplementJournal {
  private readonly scope:SupplementScope;private readonly key:string;private db?:IDBDatabase;private connection?:Promise<IDBDatabase>;private closed=false;
  constructor(scope:SupplementScope,private readonly factory:IDBFactory=globalThis.indexedDB){this.scope=checkSupplementScope(scope);this.key=JSON.stringify(['prepared-supplement-v1',this.scope]);}
  private async open(){if(this.closed||!this.factory)throw fail();if(!this.connection)this.connection=new Promise<IDBDatabase>((resolve,reject)=>{
    let failed=false;const rejectOpen=()=>{failed=true;reject(fail());};
    try{const request=this.factory.open('prepared-teacher-supplement-v1',1);request.onupgradeneeded=()=>request.result.createObjectStore('intents');request.onerror=request.onblocked=rejectOpen;request.onsuccess=()=>{const db=request.result;if(this.closed||failed){db.close();rejectOpen();return;}this.db=db;db.onversionchange=()=>this.close();resolve(db);};}catch{rejectOpen();}
  });try{return await this.connection;}catch{this.connection=undefined;throw fail();}}
  private copy(row:unknown):SupplementCommand|null{if(row===undefined)return null;try{
    if(typeof row!=='string'||row.length>16384)throw fail();const stored=JSON.parse(row);
    if(Object.keys(stored).sort().join(',')!=='command,scope,version'||stored.version!==1||JSON.stringify(checkSupplementScope(stored.scope))!==JSON.stringify(this.scope))throw fail();return copySupplementCommand(stored.command);
  }catch{throw fail();}}
  private async operation(change?:(existing:SupplementCommand|null)=>SupplementCommand|null):Promise<SupplementCommand|null>{
    try{const db=await this.open();if(this.closed)throw fail();return await new Promise((resolve,reject)=>{
      const tx=db.transaction('intents',change?'readwrite':'readonly'),store=tx.objectStore('intents');let result:SupplementCommand|null=null;
      tx.onabort=tx.onerror=()=>reject(fail());tx.oncomplete=()=>resolve(result);
      const request=store.get(this.key);request.onsuccess=()=>{try{const old=this.copy(request.result);result=change?change(old):old;
        if(change){if(result===null)store.delete(this.key);else store.put(JSON.stringify({version:1,scope:this.scope,command:copySupplementCommand(result)}),this.key);}
      }catch{tx.abort();}};
    });}catch{throw fail();}
  }
  async read(){return this.operation();}
  async prepare(input:Omit<SupplementCommand,'idempotencyKey'>):Promise<SupplementCommand>{
    const saved=await this.operation(old=>old??copySupplementCommand({idempotencyKey:crypto.randomUUID(),expectedRevision:input.expectedRevision,expectedAttemptRevision:input.expectedAttemptRevision,manifestSha256:input.manifestSha256,uploadDeadline:input.uploadDeadline,reason:input.reason}));return copySupplementCommand(saved);
  }
  async clear(original:SupplementCommand){const expected=copySupplementCommand(original);await this.operation(old=>{if(JSON.stringify(old)!==JSON.stringify(expected))throw fail();return null;});}
  close(){this.closed=true;this.db?.close();this.db=undefined;}
}
