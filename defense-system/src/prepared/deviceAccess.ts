export type DeviceKind='microphone'|'camera';
type Code='INVALID'|'UNSUPPORTED'|'CANCELLED'|'TIMED_OUT'|'PERMISSION_DENIED'|'NOT_FOUND'|'BUSY'|'UNAVAILABLE';
const messages:Record<Code,string>={INVALID:'设备检查参数无效',UNSUPPORTED:'需要安全连接和支持的桌面浏览器进行设备检查',CANCELLED:'已取消设备检查',TIMED_OUT:'等待设备权限超时，请先检查浏览器授权后重试',PERMISSION_DENIED:'设备权限被拒绝，请检查浏览器授权',NOT_FOUND:'没有找到所需设备',BUSY:'设备暂不可读，请检查是否被其他应用占用',UNAVAILABLE:'设备暂不可用，请检查设备连接后重试'};
export class DeviceAccessError extends Error{constructor(public readonly code:Code){super(messages[code]);this.name='DeviceAccessError';}}
export type DeviceEnvironment={secure:boolean;request:(constraints:MediaStreamConstraints)=>Promise<MediaStream>};
function nativeEnvironment():DeviceEnvironment{
  const media=globalThis.navigator?.mediaDevices;
  return {secure:globalThis.isSecureContext===true&&typeof media?.getUserMedia==='function',request:constraints=>media.getUserMedia(constraints)};
}
function close(stream:MediaStream){try{for(const track of stream.getTracks()){try{track.stop();}catch{/* retain bounded error; never log native labels */}}}catch{/* malformed native result */}}

/** An ignored getUserMedia prompt cannot be cancelled natively; close every stream delivered after cancellation/timeout. */
export function requestPreparedDevice(kind:DeviceKind,signal?:AbortSignal,environment:DeviceEnvironment=nativeEnvironment()):Promise<MediaStream>{
  if(kind!=='microphone'&&kind!=='camera')return Promise.reject(new DeviceAccessError('INVALID'));
  if(signal?.aborted)return Promise.reject(new DeviceAccessError('CANCELLED'));
  if(!environment.secure||typeof environment.request!=='function')return Promise.reject(new DeviceAccessError('UNSUPPORTED'));
  const constraints:MediaStreamConstraints=kind==='microphone'
    ?{audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true},video:false}
    :{audio:false,video:{width:{ideal:640,max:1280},height:{ideal:480,max:720},frameRate:{ideal:15,max:30}}};
  return new Promise<MediaStream>((resolve,reject)=>{
    let settled=false;
    const cleanup=()=>{clearTimeout(timer);signal?.removeEventListener('abort',onAbort);};
    const fail=(code:Code)=>{if(settled)return;settled=true;cleanup();reject(new DeviceAccessError(code));};
    const onAbort=()=>fail('CANCELLED');
    const timer=setTimeout(()=>fail('TIMED_OUT'),15000);
    signal?.addEventListener('abort',onAbort,{once:true});
    let requesting:Promise<MediaStream>;
    try{requesting=environment.request(constraints);}catch{fail('UNAVAILABLE');return;}
    requesting.then(stream=>{
      if(settled){close(stream);return;}
      try{
        const expected=kind==='microphone'?stream.getAudioTracks():stream.getVideoTracks();
        const forbidden=kind==='microphone'?stream.getVideoTracks():stream.getAudioTracks();
        if(expected.length!==1||forbidden.length||expected.some(t=>t.readyState!=='live'||!t.enabled)){
          close(stream);fail('UNAVAILABLE');return;
        }
      }catch{close(stream);fail('UNAVAILABLE');return;}
      settled=true;cleanup();resolve(stream);
    },error=>{
      const name=error&&typeof error==='object'?error.name:'';
      fail(name==='NotAllowedError'?'PERMISSION_DENIED':name==='NotFoundError'?'NOT_FOUND':name==='NotReadableError'?'BUSY':'UNAVAILABLE');
    });
  });
}
