import type {PreparedQuestionGrant} from '@/api/preparedAttempt';
import type {AnswerScope} from './localAudioJournal';
export type QuestionClock={mono:()=>number;wall:()=>number};
export type RequestTime={mono:number;wall:number};
export const QUESTION_CLOCK:QuestionClock={mono:()=>performance.now(),wall:()=>Date.now()};
export function questionRequestTime(clock:QuestionClock=QUESTION_CLOCK):RequestTime{return {mono:clock.mono(),wall:clock.wall()};}
function instant(v:string){if(typeof v!=='string'||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/.test(v))throw new Error('原单题授权时间无效');const value=Date.parse(v);if(!Number.isFinite(value))throw new Error('原单题授权时间无效');return value;}
const finite=(n:number)=>Number.isFinite(n)&&n>=0;
/** Conservative local enforcement, not a cryptographic proof of offline capture time. No whole/idle limit.
 * Full request latency is deducted so a slow response cannot grant extra recording time. */
export class PreparedQuestionDeadline {
  private readonly origin:RequestTime;
  private readonly budget:number;
  private left:number;
  constructor(grant:PreparedQuestionGrant,scope:AnswerScope,sent:RequestTime,private readonly clock:QuestionClock=QUESTION_CLOCK){
    if(!grant||!scope||grant.assessmentId!==scope.assessmentId||grant.attemptId!==scope.attemptId||grant.answerId!==scope.answerId||
      grant.state!=='RECORDING'||grant.mayRecord!==true||!Number.isSafeInteger(grant.answerSeconds)||grant.answerSeconds<1||grant.answerSeconds>1800||
      typeof grant.body!=='string'||!grant.body.trim())throw new Error('原作答授权无效，不能开始录音');
    const start=instant(grant.startedAt),deadline=instant(grant.answerDeadline),server=instant(grant.serverTime);
    if(deadline-start!==grant.answerSeconds*1000||server<start||!sent||!finite(sent.mono)||!finite(sent.wall))throw new Error('原作答授权时间无效');
    this.origin=questionRequestTime(clock);
    if(!finite(this.origin.mono)||!finite(this.origin.wall)||this.origin.mono<sent.mono)throw new Error('本机计时不可用，不能开始录音');
    const latency=Math.max(this.origin.mono-sent.mono,this.origin.wall-sent.wall,0);
    this.budget=Math.max(0,deadline-server-latency);this.left=this.budget;
  }
  remainingMs(){
    try{const now=questionRequestTime(this.clock);if(!finite(now.mono)||!finite(now.wall)||now.mono<this.origin.mono){this.left=0;return 0;}
      const elapsed=Math.max(now.mono-this.origin.mono,now.wall-this.origin.wall,0);
      this.left=Math.min(this.left,Math.max(0,this.budget-elapsed));return this.left;
    }catch{this.left=0;return 0;}
  }
}
