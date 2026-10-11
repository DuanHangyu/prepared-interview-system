// @vitest-environment node
import {afterEach,describe,expect,it,vi} from 'vitest';
import {IDBFactory} from 'fake-indexeddb';
import {PreparedAudioJournal,type AnswerScope} from '../../src/prepared/localAudioJournal';
const owner={origin:'http://127.0.0.1:13015',deploymentId:'prepared-local',studentId:12};
const scope=():AnswerScope=>({assessmentId:8,attemptId:crypto.randomUUID(),answerId:crypto.randomUUID()});
const journals:PreparedAudioJournal[]=[];
function journal(identity=owner,purpose:'answer'|'preflight'='answer'){
  const value=new PreparedAudioJournal(identity,new IDBFactory(),purpose);journals.push(value);return value;
}
afterEach(()=>{journals.splice(0).forEach(value=>value.close());vi.restoreAllMocks();});
describe('stable owner/assessment local writer keys',()=>{
  it('same student and assessment share one opaque key across answers, attempts and journal handles',async()=>{
    const a=await journal().captureWriterKey(scope()),b=await journal().captureWriterKey(scope());
    expect(a).toBe(b);expect(a).toMatch(/^prepared-capture-v1:[a-f0-9]{64}$/);expect(a).not.toContain(owner.origin);
  });
  it('separates student, assessment, origin, deployment and disposable trial scope',async()=>{
    const original=scope();const keys=await Promise.all([
      journal().captureWriterKey(original),journal({...owner,studentId:13}).captureWriterKey(original),
      journal().captureWriterKey({...original,assessmentId:9}),journal({...owner,origin:'https://prepared.example'}).captureWriterKey(original),
      journal({...owner,deploymentId:'prepared-other'}).captureWriterKey(original),
      journal(owner,'preflight').captureWriterKey(original),journal(owner,'preflight').captureWriterKey(scope()),
    ]);expect(new Set(keys).size).toBe(keys.length);
  });
  it('snapshots validated scope before crypto; no database creation or answer authorization occurs',async()=>{
    const original=scope();const expected=await journal().captureWriterKey(original),value=journal();
    const open=vi.spyOn(IDBFactory.prototype,'open');const pending=value.captureWriterKey(original);original.assessmentId=99;
    expect(await pending).toBe(expected);expect(open).not.toHaveBeenCalled();
  });
  it('rejects invalid and closed handles and bounds crypto failure',async()=>{
    const value=journal();await expect(value.captureWriterKey({...scope(),answerId:'not-uuid'})).rejects.toMatchObject({code:'INVALID'});
    vi.spyOn(crypto.subtle,'digest').mockRejectedValueOnce(new Error('private platform data'));
    await expect(value.captureWriterKey(scope())).rejects.toMatchObject({code:'WRITE_FAILED'});
    value.close();await expect(value.captureWriterKey(scope())).rejects.toMatchObject({code:'CLOSED'});
  });
  it('closing during a pending digest does not return a usable owner key',async()=>{
    const real=crypto.subtle.digest.bind(crypto.subtle);let release!:()=>void;
    vi.spyOn(crypto.subtle,'digest').mockImplementationOnce(async(...args)=>{await new Promise<void>(r=>{release=r;});return real(...args);});
    const value=journal();const pending=value.captureWriterKey(scope());const rejected=expect(pending).rejects.toMatchObject({code:'CLOSED'});
    await vi.waitFor(()=>expect(release).toBeTypeOf('function'));value.close();release();await rejected;
  });
});
