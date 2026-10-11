import type { RetentionScope,RetentionCommand } from '@/api/preparedRetention';
import { checkRetentionScope,copyRetentionCommand } from './retentionProof';
const fail=()=>Error('原留存保护请求在本机保存或核验失败，请保留本机内容，不要重复创建请求');
/** Origin-local command metadata only; IndexedDB commits serialize separate tabs.
 * No audio, credentials, cached authorization, automatic POST or replacement key. */
export class PreparedRetentionJournal {
  private readonly scope:RetentionScope;private readonly key:string;private db?:IDBDatabase;private connection?:Promise<IDBDatabase>;private closed=false;
  constructor(scope:RetentionScope,private readonly factory:IDBFactory=globalThis.indexedDB){this.scope=checkRetentionScope(scope);this.key=JSON.stringify(['prepared-retention-v1',this.scope]);}
  private async open(){if(this.closed||!this.factory)throw fail();if(!this.connection)this.connection=new Promise<IDBDatabase>((resolve,reject)=>{
    let failed=false;const rejectOpen=()=>{failed=true;reject(fail());};
    try{const request=this.factory.open('prepared-teacher-retention-v1',1);request.onupgradeneeded=()=>request.result.createObjectStore('intents');request.onerror=request.onblocked=rejectOpen;request.onsuccess=()=>{const db=request.result;if(this.closed||failed){db.close();rejectOpen();return;}this.db=db;db.onversionchange=()=>this.close();resolve(db);};}catch{rejectOpen();}
  });try{return await this.connection;}catch{this.connection=undefined;throw fail();}}
  private copy(row:unknown):RetentionCommand|null{if(row===undefined)return null;try{
    if(typeof row!=='string'||row.length>16384)throw fail();const stored=JSON.parse(row);if(Object.keys(stored).sort().join(',')!=='command,scope,version'||stored.version!==1||JSON.stringify(checkRetentionScope(stored.scope))!==JSON.stringify(this.scope))throw fail();return copyRetentionCommand(stored.command);
  }catch{throw fail();}}
  private async operation(change?:(old:RetentionCommand|null)=>RetentionCommand|null):Promise<RetentionCommand|null>{
    try{const db=await this.open();if(this.closed)throw fail();return await new Promise((resolve,reject)=>{
      const tx=db.transaction('intents',change?'readwrite':'readonly'),store=tx.objectStore('intents');let result:RetentionCommand|null=null;tx.onabort=tx.onerror=()=>reject(fail());tx.oncomplete=()=>resolve(result);
      const request=store.get(this.key);request.onsuccess=()=>{try{const old=this.copy(request.result);result=change?change(old):old;if(change){if(result===null)store.delete(this.key);else store.put(JSON.stringify({version:1,scope:this.scope,command:copyRetentionCommand(result)}),this.key);}}catch{tx.abort();}};
    });}catch{throw fail();}
  }
  async read(){return this.operation();}
  async prepare(input:Omit<RetentionCommand,'idempotencyKey'>):Promise<RetentionCommand>{const saved=await this.operation(old=>old??copyRetentionCommand({idempotencyKey:crypto.randomUUID(),expectedRevision:input.expectedRevision,action:input.action,reason:input.reason}));return copyRetentionCommand(saved);}
  async clear(original:RetentionCommand){const expected=copyRetentionCommand(original);await this.operation(old=>{if(JSON.stringify(old)!==JSON.stringify(expected))throw fail();return null;});}
  close(){this.closed=true;this.db?.close();this.db=undefined;}
}
