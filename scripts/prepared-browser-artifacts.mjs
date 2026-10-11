import fs from 'node:fs';
import path from 'node:path';

// Test diagnostics may contain session/answer material. Each CLI owns a new
// private directory; only a config reload or its direct Playwright child reuses
// it. Never hand Playwright an inherited shared directory it would clear.
export function prepareBrowserArtifacts(project,env,identity={pid:process.pid,ppid:process.ppid,entry:process.argv[1]??''}) {
  const check=ok=>{if(!ok)throw Error('PRIVATE_BROWSER_ARTIFACT_SCOPE_REQUIRED');};
  try {
    check(path.isAbsolute(project)&&path.basename(project)==='prepared-interview-system'&&fs.realpathSync(project)===project);
    check(Number.isSafeInteger(identity.pid)&&identity.pid>1&&Number.isSafeInteger(identity.ppid)&&identity.ppid>0);
    const owner=process.getuid();
    function directory(file,mode){const stat=fs.lstatSync(file);check(stat.isDirectory()&&!stat.isSymbolicLink()&&stat.uid===owner&&fs.realpathSync(file)===file&&(mode===undefined||(stat.mode&0o777)===mode));}
    directory(project);const runtime=path.join(project,'.runtime');
    if(!fs.existsSync(runtime))fs.mkdirSync(runtime,{mode:0o700});directory(runtime,0o700);
    let scope=env.PREPARED_BROWSER_ARTIFACT_SCOPE;
    if(scope!==undefined){
      check(typeof scope==='string'&&path.isAbsolute(scope)&&scope===path.resolve(scope)&&path.dirname(scope)===runtime&&/^browser-[A-Za-z0-9]{6}$/.test(path.basename(scope)));directory(scope,0o700);
      const marker=path.join(scope,'scope.json'),stat=fs.lstatSync(marker);check(stat.isFile()&&!stat.isSymbolicLink()&&stat.uid===owner&&(stat.mode&0o777)===0o600&&stat.size<=4096);
      const raw=fs.readFileSync(marker,'utf8'),proof=JSON.parse(raw);check(JSON.stringify(proof)+'\n'===raw&&Object.keys(proof).sort().join(',')==='ownerPid,schemaVersion,scope'&&proof.schemaVersion===1&&proof.scope===path.basename(scope)&&Number.isSafeInteger(proof.ownerPid)&&proof.ownerPid>1);
      const worker=/(?:^|\/)playwright\/lib\/(?:worker\/workerProcessEntry|loader\/loaderProcessEntry)\.js$/.test(identity.entry.replaceAll('\\','/'));
      check(proof.ownerPid===identity.pid||(proof.ownerPid===identity.ppid&&worker));
    }else{
      scope=fs.mkdtempSync(path.join(runtime,'browser-'));directory(scope,0o700);
      fs.writeFileSync(path.join(scope,'scope.json'),JSON.stringify({schemaVersion:1,ownerPid:identity.pid,scope:path.basename(scope)})+'\n',{flag:'wx',mode:0o600});env.PREPARED_BROWSER_ARTIFACT_SCOPE=scope;
    }
    const outputDir=path.join(scope,'results'),reportDir=path.join(scope,'report');
    for(const file of [outputDir,reportDir])if(fs.existsSync(file)||fs.lstatSync(file,{throwIfNoEntry:false}))directory(file);
    return {outputDir,reportDir};
  }catch(invalid){throw Error('PRIVATE_BROWSER_ARTIFACT_SCOPE_REQUIRED');}
}
