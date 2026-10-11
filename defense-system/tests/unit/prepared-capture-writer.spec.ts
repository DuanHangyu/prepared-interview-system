// @vitest-environment node
import {afterEach,describe,expect,it,vi} from 'vitest';
import {acquireCaptureWriter,type WriterEnvironment} from '../../src/prepared/captureWriter';

const key='prepared-capture-v1:'+ 'a'.repeat(64);
function manager(){
  const held=new Set<string>();
  const request=vi.fn<NonNullable<WriterEnvironment['request']>>(async(name,options,callback)=>{
    expect(options).toEqual({mode:'exclusive',ifAvailable:true});
    if(held.has(name))return callback(null);
    held.add(name);
    try{await callback({});}finally{held.delete(name);}
  });
  return {held,environment:{secure:true,request} satisfies WriterEnvironment,request};
}
afterEach(()=>{vi.useRealTimers();vi.unstubAllGlobals();});
describe('exclusive local capture writer — coordination, not server authorization',()=>{
  it('holds across async work, rejects a duplicate without queuing, and releases idempotently',async()=>{
    const {held,environment,request}=manager();const lease=await acquireCaptureWriter(key,environment);
    expect(held.has(key)).toBe(true);
    await expect(acquireCaptureWriter(key,environment)).rejects.toMatchObject({code:'BUSY'});
    expect(request).toHaveBeenCalledTimes(2);lease.release();lease.release();
    await vi.waitFor(()=>expect(held.size).toBe(0));(await acquireCaptureWriter(key,environment)).release();
  });
  it('coordinates only matching opaque resources, not every student or assessment',async()=>{
    const {held,environment}=manager();const a=await acquireCaptureWriter(key,environment);
    const b=await acquireCaptureWriter('prepared-capture-v1:'+'b'.repeat(64),environment);
    expect(held.size).toBe(2);a.release();b.release();
  });
  it.each(['', 'student:12', '-reserved', 'prepared-capture-v1:'+'A'.repeat(64), 'prepared-capture-v1:'+'a'.repeat(65)])('rejects invalid or identifying lock names before native access: %s',async(value)=>{
    const {environment,request}=manager();await expect(acquireCaptureWriter(value,environment)).rejects.toMatchObject({code:'INVALID'});expect(request).not.toHaveBeenCalled();
  });
  it('fails closed in insecure or unsupported contexts without a racy storage fallback',async()=>{
    const {environment,request}=manager();
    await expect(acquireCaptureWriter(key,{...environment,secure:false})).rejects.toMatchObject({code:'UNSUPPORTED'});
    await expect(acquireCaptureWriter(key,{secure:true})).rejects.toMatchObject({code:'UNSUPPORTED'});expect(request).not.toHaveBeenCalled();
    await expect(acquireCaptureWriter(key)).rejects.toMatchObject({code:'UNSUPPORTED'});
  });
  it('times out boundedly and releases a late grant without returning a usable lease',async()=>{
    vi.useFakeTimers();let callback!:Parameters<NonNullable<WriterEnvironment['request']>>[2];
    const environment:WriterEnvironment={secure:true,request:async(_name,_options,cb)=>{callback=cb;await new Promise(()=>{});}};
    const pending=acquireCaptureWriter(key,environment);const rejected=expect(pending).rejects.toMatchObject({code:'TIMED_OUT'});
    await vi.advanceTimersByTimeAsync(5000);await rejected;await expect(Promise.resolve(callback({}))).resolves.toBeUndefined();
  });
  it('cancellation before or during acquisition never starts a late capture',async()=>{
    const controller=new AbortController();controller.abort();const {environment,request}=manager();
    await expect(acquireCaptureWriter(key,environment,controller.signal)).rejects.toMatchObject({code:'CANCELLED'});expect(request).not.toHaveBeenCalled();
    let callback!:Parameters<NonNullable<WriterEnvironment['request']>>[2];const live=new AbortController();
    const pending=acquireCaptureWriter(key,{secure:true,request:async(_n,_o,cb)=>{callback=cb;await new Promise(()=>{});}},live.signal);
    const rejected=expect(pending).rejects.toMatchObject({code:'CANCELLED'});live.abort();await rejected;await expect(Promise.resolve(callback({}))).resolves.toBeUndefined();
  });
  it.each(['throw','reject','settle'])('bounds native %s without leaking raw exceptions or waiting forever',async(mode)=>{
    const request:NonNullable<WriterEnvironment['request']>=()=>{
      if(mode==='throw')throw new Error('private platform details');
      return mode==='reject'?Promise.reject(new Error('private platform details')):Promise.resolve();
    };
    await expect(acquireCaptureWriter(key,{secure:true,request})).rejects.toMatchObject({code:'UNAVAILABLE',message:'本地录音独占保护不可用，请保留原页面'});
  });
  it('does not cancel a granted writer when its acquisition signal later aborts',async()=>{
    const {held,environment}=manager();const controller=new AbortController();const lease=await acquireCaptureWriter(key,environment,controller.signal);
    controller.abort();expect(held.has(key)).toBe(true);lease.release();
  });
  it('reports unexpected native loss once, including late subscribers; observer faults are isolated',async()=>{
    let reject!:(error:unknown)=>void;
    const environment:WriterEnvironment={secure:true,request:(_n,_o,cb)=>{void cb({});return new Promise((_r,r)=>{reject=r;});}};
    const lease=await acquireCaptureWriter(key,environment);const lost=vi.fn(()=>{throw new Error('observer');});
    lease.onLost(lost);reject(new DOMException('private','AbortError'));await vi.waitFor(()=>expect(lost).toHaveBeenCalledTimes(1));
    const late=vi.fn();lease.onLost(late);expect(late).toHaveBeenCalledTimes(1);lease.release();expect(lost).toHaveBeenCalledTimes(1);
  });
  it('normal release is not loss and unsubscribed observers are not notified',async()=>{
    const {environment}=manager();const lease=await acquireCaptureWriter(key,environment);const lost=vi.fn();lease.onLost(lost)();lease.release();
    await Promise.resolve();expect(lost).not.toHaveBeenCalled();
  });
  it('uses the actual default native adapter when capability exists, and bounds platform access failure',async()=>{
    const {environment,request}=manager();vi.stubGlobal('isSecureContext',true);vi.stubGlobal('navigator',{locks:{request}});
    const lease=await acquireCaptureWriter(key);expect(request).toHaveBeenCalledWith(key,{mode:'exclusive',ifAvailable:true},expect.any(Function));lease.release();
    vi.stubGlobal('navigator',{get locks(){throw new Error('private');}});
    await expect(acquireCaptureWriter(key)).rejects.toMatchObject({code:'UNSUPPORTED'});expect(environment.secure).toBe(true);
  });
  it('unexpected fulfilled native lifetime is also loss, but removed listeners cannot interfere',async()=>{
    let finish!:()=>void;const environment:WriterEnvironment={secure:true,request:(_n,_o,cb)=>{void cb({});return new Promise(r=>{finish=()=>r(undefined);});}};
    const lease=await acquireCaptureWriter(key,environment),lost=vi.fn(),removed=vi.fn();lease.onLost(lost);lease.onLost(removed)();finish();
    await vi.waitFor(()=>expect(lost).toHaveBeenCalledTimes(1));expect(removed).not.toHaveBeenCalled();lease.release();
  });
});
