import { describe, expect, it } from "vitest";
import assessmentForm from "../../src/views/teacherDashboard/components/HandleAssessment.vue?raw";
import studentApi from "../../src/api/student.ts?raw";
import teacherApi from "../../src/api/teacher.ts?raw";
import assessmentList from "../../src/views/teacherDashboard/AssessSetting.vue?raw";

describe("assessment form data loading contract", () => {
  it("does not issue the retired oversized student page request", () => {
    expect(assessmentForm).not.toContain("getStudentPageList");
    expect(assessmentForm).not.toContain("size: 100000");
  });

  it("does not publish the retired unbounded student-list client", () => {
    expect(studentApi).not.toContain("getAllStudent");
    expect(studentApi).not.toContain('url: "/backend/student/all"');
  });

  it("loads student options only when a class is expanded", () => {
    expect(assessmentForm).toContain(':load-data="loadSchoolClassStudents"');
    expect(assessmentForm).toContain("getSchoolClassStudentOptions");
    expect(studentApi).toContain("schoolClass");
  });

  it("searches a bounded teacher option endpoint remotely", () => {
    expect(assessmentForm).toContain('@search="handleTeacherSearch"');
    expect(assessmentForm).toContain(':filter-option="false"');
    expect(teacherApi).toContain("params: { keyword }");
  });

  it("treats malformed option payloads as empty lists", () => {
    expect(assessmentForm).toContain("const responseItems =");
    expect(assessmentForm.match(/responseItems\(res\?\.data\)/g)).toHaveLength(3);
  });
});

describe("prepared assessment entry wording, not live integration", () => {
  it("does not present legacy duration or answerTime as the prepared question deadline", () => {
    expect(assessmentList).not.toContain('{{ item?.duration }}');
    expect(assessmentList).not.toContain('{{ item?.answerTime }}');
    expect(assessmentList).toContain('逐题设置（准备题目）');
    expect(assessmentList).toContain('预置题目数量');
  });
  it("describes fixed questions and student allocation rather than realtime follow-up", () => {
    expect(assessmentList).toContain('配置预置面试基础信息、题目数量、学生分配与预约安排');
    expect(assessmentList).not.toContain('配置考核主题、答辩规则、提问追问与预约安排');
  });
});
