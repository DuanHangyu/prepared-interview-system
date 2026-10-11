import fs from 'node:fs';
import path from 'node:path';
// Playwright transforms host-side test imports to CJS in this project. Avoid
// import.meta so the same boundary runs in native ESM and that host loader.
const cwd=process.cwd(),projectRoot=path.basename(cwd)==='defense-system'?path.dirname(cwd):cwd;
const uuid=/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/;
export function checkPreparedBrowserProof(project,env,id) {
  try {
    if(!Number.isSafeInteger(id)||id<1)throw Error();
    const name=env.PREPARED_VALIDATION_BROWSER_DESCRIPTOR;
    if(!name){if(id<=8||env.PREPARED_VALIDATION_BROWSER_RUN_ID)throw Error();return true;} // existing canonical fixture exclusion is not relaxed
    if(env.LIVE_E2E!=='1'||!uuid.test(env.PREPARED_VALIDATION_BROWSER_RUN_ID??''))throw Error();
    if(!path.isAbsolute(project)||fs.realpathSync(project)!==project||path.basename(project)!=='prepared-interview-system')throw Error();
    const file=path.resolve(name),run=path.dirname(file);
    if(name!==file||path.basename(file)!=='browser-database.json'||path.dirname(run)!==path.join(project,'.runtime')||!/^validation-[A-Za-z0-9]{6}$/.test(path.basename(run)))throw Error();
    for(const [target,mode,isDirectory]of [[path.join(project,'.runtime'),0o700,true],[run,0o700,true],[file,0o600,false]]){
      const stat=fs.lstatSync(target);
      if(stat.isSymbolicLink()||stat.uid!==process.getuid()||(stat.mode&0o777)!==mode||fs.realpathSync(target)!==target||(isDirectory?!stat.isDirectory():!stat.isFile())||(!isDirectory&&stat.size>4096))throw Error();
    }
    const proof=JSON.parse(fs.readFileSync(file,'utf8'));
    if(!proof||Object.keys(proof).sort().join(',')!==['schemaVersion','nativePid','ownerPid','nativePort','nonce'].sort().join(',')||proof.schemaVersion!==1
      ||proof.nonce!==env.PREPARED_VALIDATION_BROWSER_RUN_ID||!Number.isInteger(proof.nativePort)||proof.nativePort<1024||proof.nativePort>65535||proof.nativePort===13317)throw Error();
    for(const pid of [proof.nativePid,proof.ownerPid]){if(!Number.isSafeInteger(pid)||pid<=0)throw Error();process.kill(pid,0);}
    return true;
  }catch(invalid){throw new Error('INDEPENDENT_BROWSER_FIXTURE_REQUIRED');}
}
export function assertPreparedFixture(env,id){return checkPreparedBrowserProof(projectRoot,env,id);}
