import { describe,it,expect,vi } from 'vitest';
import request from '@/utils/request';
import {getPreparedAudio,prepareQuestionAudio,recoverQuestionAudio,getAudioPreview} from '@/api/preparedAudio';
vi.mock('@/utils/request',()=>({default:vi.fn()}));
describe('audio HTTP contracts',()=>{
  it('uses scoped short HTTP commands without socket or local audio paths',()=>{
    const c={expectedRevision:2,idempotencyKey:'key',questionId:'q',expectedConfigurationSha256:'hash'};
    getPreparedAudio(21);expect(request).toHaveBeenLastCalledWith({url:'/backend/prepared-papers/21/audio',method:'get'});
    prepareQuestionAudio(21,c);expect(request).toHaveBeenLastCalledWith({url:'/backend/prepared-papers/21/audio',method:'post',data:c});
    const r={expectedRevision:2,idempotencyKey:'key'};recoverQuestionAudio(21,'asset',r);expect(request).toHaveBeenLastCalledWith({url:'/backend/prepared-papers/21/audio/asset/recover',method:'post',data:r});
    getAudioPreview(21,'asset');expect(request).toHaveBeenLastCalledWith({url:'/backend/prepared-papers/21/audio/asset/preview',method:'get'});
  });
});
