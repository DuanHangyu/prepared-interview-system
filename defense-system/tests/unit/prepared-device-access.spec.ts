// @vitest-environment node
import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {requestPreparedDevice,type DeviceEnvironment} from '../../src/prepared/deviceAccess';
let environment:DeviceEnvironment,stop:ReturnType<typeof vi.fn>,stream:MediaStream;
function device(kind:'audio'|'video'){const track={kind,readyState:'live',enabled:true,muted:false,stop};return {getTracks:()=>[track],getAudioTracks:()=>kind==='audio'?[track]:[],getVideoTracks:()=>kind==='video'?[track]:[]} as unknown as MediaStream;}
beforeEach(()=>{stop=vi.fn();stream=device('audio');environment={secure:true,request:vi.fn().mockResolvedValue(stream)};});
afterEach(()=>vi.useRealTimers());
describe('bounded prepared device permission requests',()=>{
  it('microphone acquisition never requests video and returns an owned usable audio stream',async()=>{
    expect(await requestPreparedDevice('microphone',undefined,environment)).toBe(stream);
    expect(environment.request).toHaveBeenCalledWith({audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true},video:false});expect(stop).not.toHaveBeenCalled();
  });
  it('camera preview never requests audio or couples itself to a microphone grant',async()=>{
    stream=device('video');vi.mocked(environment.request).mockResolvedValue(stream);
    expect(await requestPreparedDevice('camera',undefined,environment)).toBe(stream);
    expect(vi.mocked(environment.request).mock.calls[0][0]).toMatchObject({audio:false,video:{width:{ideal:640,max:1280},height:{ideal:480,max:720},frameRate:{ideal:15,max:30}}});
  });
  it('abort before permission does not open a device prompt',async()=>{
    const controller=new AbortController();controller.abort();await expect(requestPreparedDevice('microphone',controller.signal,environment)).rejects.toMatchObject({code:'CANCELLED'});
    expect(environment.request).not.toHaveBeenCalled();
  });
  it('abort while the browser prompt is unresolved releases a late stream and never returns it to a stale page',async()=>{
    let allow!:(stream:MediaStream)=>void;vi.mocked(environment.request).mockImplementation(()=>new Promise(resolve=>{allow=resolve;}));
    const controller=new AbortController();const result=requestPreparedDevice('microphone',controller.signal,environment);
    controller.abort();await expect(result).rejects.toMatchObject({code:'CANCELLED'});allow(stream);await Promise.resolve();expect(stop).toHaveBeenCalledTimes(1);
  });
  it('ignored permission prompt has a bounded timeout and closes a late approved device',async()=>{
    vi.useFakeTimers({toFake:['setTimeout','clearTimeout']});let allow!:(stream:MediaStream)=>void;
    vi.mocked(environment.request).mockImplementation(()=>new Promise(resolve=>{allow=resolve;}));
    const result=requestPreparedDevice('microphone',undefined,environment);const rejection=expect(result).rejects.toMatchObject({code:'TIMED_OUT'});
    await vi.advanceTimersByTimeAsync(15000);await rejection;allow(stream);await Promise.resolve();expect(stop).toHaveBeenCalledTimes(1);
  });
  it.each([['NotAllowedError','PERMISSION_DENIED'],['NotFoundError','NOT_FOUND'],['NotReadableError','BUSY'],['OverconstrainedError','UNAVAILABLE'],['unknown','UNAVAILABLE']])('maps %s without device labels or raw messages',async(name,code)=>{
    vi.mocked(environment.request).mockRejectedValue(Object.assign(new Error('private hardware path and labels'),{name}));
    await expect(requestPreparedDevice('microphone',undefined,environment)).rejects.toMatchObject({code});
  });
  it('untrusted mixed or unavailable tracks are closed instead of being accidentally recorded',async()=>{
    stream=device('video');vi.mocked(environment.request).mockResolvedValue(stream);
    await expect(requestPreparedDevice('microphone',undefined,environment)).rejects.toMatchObject({code:'UNAVAILABLE'});expect(stop).toHaveBeenCalledTimes(1);
  });
  it('insecure and unsupported environments fail without calling a browser request',async()=>{
    environment.secure=false;await expect(requestPreparedDevice('microphone',undefined,environment)).rejects.toMatchObject({code:'UNSUPPORTED'});
    expect(environment.request).not.toHaveBeenCalled();await expect(requestPreparedDevice('video-and-audio' as never,undefined,environment)).rejects.toMatchObject({code:'INVALID'});
  });
  it('a synchronous request exception is bounded too',async()=>{
    environment.request=()=>{throw new Error('native private exception');};await expect(requestPreparedDevice('microphone',undefined,environment)).rejects.toMatchObject({code:'UNAVAILABLE'});
  });
});
