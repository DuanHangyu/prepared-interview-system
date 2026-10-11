/** Same storage-bucket coordination only; never a server grant, identity check or cross-device lease. */
type WriterCode='INVALID'|'UNSUPPORTED'|'BUSY'|'TIMED_OUT'|'CANCELLED'|'UNAVAILABLE';
const messages:Record<WriterCode,string>={
  INVALID:'本地录音保护参数无效',UNSUPPORTED:'此浏览器无法提供本地录音独占保护',
  BUSY:'此考核已在另一个页面录音，请保留原页面',TIMED_OUT:'本地录音独占检查超时，请保留原页面',
  CANCELLED:'本地录音独占检查已取消',UNAVAILABLE:'本地录音独占保护不可用，请保留原页面',
};
export class WriterError extends Error {
  constructor(public readonly code:WriterCode){super(messages[code]);this.name='WriterError';}
}
export type WriterEnvironment={
  secure:boolean;
  request?:(name:string,options:{mode:'exclusive';ifAvailable:true},callback:(lock:object|null)=>Promise<void>|void)=>Promise<unknown>;
};
export type WriterLease={release:()=>void;onLost:(listener:()=>void)=>()=>void};
function nativeEnvironment():WriterEnvironment {
  try{
    return {secure:globalThis.isSecureContext===true,
      request:typeof globalThis.navigator?.locks?.request==='function'?(name,options,callback)=>navigator.locks.request(name,options,callback):undefined};
  }catch{return {secure:false};}
}
/** Conditional acquisition, never queued/stealing. Timed-out/cancelled late grants are immediately released. */
export function acquireCaptureWriter(key:string,environment:WriterEnvironment=nativeEnvironment(),signal?:AbortSignal):Promise<WriterLease>{
  if(typeof key!=='string'||!/^prepared-capture-v1:[a-f0-9]{64}$/.test(key))return Promise.reject(new WriterError('INVALID'));
  if(!environment.secure||typeof environment.request!=='function')return Promise.reject(new WriterError('UNSUPPORTED'));
  if(signal?.aborted)return Promise.reject(new WriterError('CANCELLED'));
  return new Promise<WriterLease>((resolve,reject)=>{
    let settled=false,granted=false,released=false,lost=false;
    const listeners=new Set<()=>void>();
    let endHold!:()=>void;const hold=new Promise<void>(done=>{endHold=done;});
    const notify=(listener:()=>void)=>{try{listener();}catch{/* Observers cannot prevent preservation or release. */}};
    const cleanup=()=>{clearTimeout(timer);signal?.removeEventListener('abort',cancel);};
    const fail=(code:WriterCode)=>{if(settled)return;settled=true;cleanup();endHold();reject(new WriterError(code));};
    const cancel=()=>fail('CANCELLED');
    const timer=setTimeout(()=>fail('TIMED_OUT'),5000);
    signal?.addEventListener('abort',cancel,{once:true});
    const finish=()=>{
      if(!granted){fail('UNAVAILABLE');return;}
      if(released||lost)return;
      lost=true;endHold();for(const listener of Array.from(listeners))notify(listener);listeners.clear();
    };
    try{
      const requesting=environment.request!(key,{mode:'exclusive',ifAvailable:true},lock=>{
        if(settled)return;
        if(lock===null){fail('BUSY');return;}
        settled=true;granted=true;cleanup();
        resolve({
          release:()=>{if(released)return;released=true;listeners.clear();endHold();},
          onLost:listener=>{if(lost)notify(listener);else if(!released)listeners.add(listener);return ()=>{listeners.delete(listener);};},
        });
        return hold;
      });
      // A granted lock lasts for the native callback promise, not the acquisition promise.
      void requesting.then(finish,finish);
    }catch{finish();}
  });
}
