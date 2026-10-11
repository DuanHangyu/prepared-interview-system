import { readFileSync } from 'node:fs';
import { expect,it,vi } from 'vitest';
import { ssrRenderAttrs } from '@vue/server-renderer';
// Regression of installed dependencies only; not a claim that this SPA uses SSR
// or that a remote attacker can currently reach either advisory's affected path.
const atLeast=(value:string,minimum:number[])=>{const v=value.split('.').map(Number);return v.length===3&&v.every(Number.isSafeInteger)&&(v[0]>minimum[0]||v[0]===minimum[0]&&(v[1]>minimum[1]||v[1]===minimum[1]&&v[2]>=minimum[2]));};
it('rejects CR-delimited injected attribute names in the actual Vue SSR helper',()=>{
  const warn=vi.spyOn(console,'warn').mockImplementation(()=>{}),error=vi.spyOn(console,'error').mockImplementation(()=>{});
  try{expect(ssrRenderAttrs({['x\rautofocus\ronfocus']:'void 0'})).toBe('');}finally{warn.mockRestore();error.mockRestore();}
});
it('locks every installed Vue/server-renderer copy at or above the stable security fix',()=>{
  const lock=JSON.parse(readFileSync('package-lock.json','utf8'));let count=0;
  for(const [name,value] of Object.entries(lock.packages) as [string,{version?:string}][])if(/(?:^|\/)node_modules\/(?:vue|@vue\/server-renderer)$/.test(name)){count++;expect(atLeast(value.version!,[3,5,42]),name).toBe(true);}expect(count).toBeGreaterThanOrEqual(2);
});
it('locks every source-map-js copy at or above the DoS fix',()=>{
  const lock=JSON.parse(readFileSync('package-lock.json','utf8'));let count=0;
  for(const [name,value] of Object.entries(lock.packages) as [string,{version?:string}][])if(/(?:^|\/)node_modules\/source-map-js$/.test(name)){count++;expect(atLeast(value.version!,[1,2,2]),name).toBe(true);}expect(count).toBeGreaterThan(0);
});
