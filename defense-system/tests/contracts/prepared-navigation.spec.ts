import { describe, expect, it } from 'vitest';
import router from '../../src/router/index.ts?raw';
import settings from '../../src/views/teacherDashboard/AssessSetting.vue?raw';

describe('prepared question editing entry', () => {
  it('is reachable from assessment management instead of an isolated demo page', () => {
    expect(settings).toContain('准备题目');
    expect(settings).toContain('/prepared-paper/');
    expect(router).toContain('PreparedPaperPage.vue');
    expect(router.match(/path: "\/prepared-paper\/:assessmentId"/g)).toHaveLength(2);
  });
  it('teacher review is reachable from management and registered only for teacher/admin routes',()=>{
    expect(settings).toContain('预置面试评阅');expect(settings).toContain('/prepared-review/');expect(router).toContain('PreparedReviewPage.vue');
    expect(router.match(/path: "\/prepared-review\/:assessmentId"/g)).toHaveLength(2);
  });
});
