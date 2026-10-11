import {PreparedAnswerCapture,type RecorderEnvironment} from './answerCapture';
import type {PreparedAudioJournal,AnswerScope} from './localAudioJournal';
import {PreparedQuestionDeadline} from './questionDeadline';
export class AuthorizedCaptureError extends Error {
  readonly code='AUTHORIZATION_EXPIRED';
  constructor(){super('本题原作答时限已结束，请保留原内容，不可重新录音');this.name='AuthorizedCaptureError';}
}
/** Monitor starts BEFORE async writer/storage preparation. Offline does not suspend the original deadline.
 * The local timer is conservative enforcement, not proof against browser suspension or clock tampering. */
export async function startAuthorizedAnswerCapture(input:{journal:PreparedAudioJournal;scope:AnswerScope;ordinal:number;stream:MediaStream;deadline:PreparedQuestionDeadline},environment?:RecorderEnvironment){
  const release=()=>{for(const track of input?.stream?.getTracks?.()??[])track.stop();};
  if(!(input?.deadline instanceof PreparedQuestionDeadline)||input.deadline.remainingMs()<=0){release();throw new AuthorizedCaptureError();}
  let capture:PreparedAnswerCapture|undefined,expired=false,unsubscribe:(()=>void)|undefined;
  const cleanup=()=>{clearInterval(timer);unsubscribe?.();};
  const check=()=>{if(expired||input.deadline.remainingMs()>0)return;expired=true;if(capture)void capture.stop();else release();};
  const timer=setInterval(check,100);
  try{
    capture=await PreparedAnswerCapture.start(input,environment);check();
    unsubscribe=capture.subscribe(value=>{if(value.phase!=='RECORDING')cleanup();});
    if(capture.snapshot().phase!=='RECORDING')cleanup();
    return capture;
  }catch(error){cleanup();release();throw error;}
}
