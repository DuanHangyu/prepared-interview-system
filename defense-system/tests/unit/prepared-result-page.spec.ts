import {mount} from '@vue/test-utils';
import {beforeEach,it,expect,vi} from 'vitest';
import {reactive} from 'vue';
import PreparedResultPage from '@/views/prepared/PreparedResultPage.vue';
const f=vi.hoisted(()=>({auth:{id:'8',role:'STUDENT',authenticated:true},route:{params:{assessmentId:'21'}},push:vi.fn()}));
vi.mock('@/stores/auth',()=>({useAuthStore:()=>reactive(f.auth)}));
vi.mock('vue-router',()=>({useRoute:()=>reactive(f.route),useRouter:()=>({push:f.push})}));
beforeEach(()=>{f.auth.id='8';f.auth.role='STUDENT';f.auth.authenticated=true;f.route.params.assessmentId='21';vi.clearAllMocks();});
const render=()=>mount(PreparedResultPage,{global:{stubs:{PreparedStudentResult:{props:['studentId','assessmentId'],template:'<div data-testid="result-stub">{{studentId}} / {{assessmentId}}</div>'}}}});
it('mounts the current authenticated owner, reacts to identity changes and removes private content on logout',async()=>{
  const w=render();expect(w.get('[data-testid="result-stub"]').text()).toBe('8 / 21');await w.get('button').trigger('click');expect(f.push).toHaveBeenCalledWith('/studentDashboard');
  await w.findAll('button')[1].trigger('click');expect(f.push).toHaveBeenLastCalledWith({name:'StudentStudyDetail',query:{id:21}});
  reactive(f.auth).authenticated=false;await w.vm.$nextTick();expect(w.find('[data-testid="result-stub"]').exists()).toBe(false);expect(w.get('[role="alert"]').text()).toContain('身份');w.unmount();
});
it('never reads results for invalid routes, unknown accounts or management roles',()=>{
  for(const id of ['0','-1','1.5','bad','2147483648','']){f.route.params.assessmentId=id;const w=render();expect(w.find('[data-testid="result-stub"]').exists()).toBe(false);w.unmount();}
  f.route.params.assessmentId='21';f.auth.role='teacher';const w=render();expect(w.find('[data-testid="result-stub"]').exists()).toBe(false);w.unmount();
  f.auth.role='STUDENT';f.auth.id='';const missing=render();expect(missing.find('[data-testid="result-stub"]').exists()).toBe(false);missing.unmount();
});
