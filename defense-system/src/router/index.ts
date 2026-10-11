import { createRouter, createWebHistory } from "vue-router";
import { Layout } from "@/components/Layout";
import StudentContainter from "@/components/Layout/componets/StudentContainter.vue";

const routes = [
  {
    path: "/login",
    name: "Login",
    component: () => import(/* webpackPreload: true */ "@/views/Login.vue"),
  },
  {
    path: "/:pathMatch(.*)*",
    component: () =>
      import(/* webpackPrefetch: true */ "@/views/error/404.vue"),
    hidden: true,
  },
];

export const studnetRoutes = [
  {
    path: "/",
    component: StudentContainter,
    redirect: "/studentDashboard",
    children: [
      {
        path: "/prepared-results/:assessmentId",
        name: "PreparedResults",
        component: () => import("@/views/prepared/PreparedResultPage.vue"),
        meta: { role: ["student"] },
      },
      {
        path: "/prepared-interview/:assessmentId/preflight",
        name: "PreparedPreflight",
        component: () => import("@/views/prepared/PreparedPreflightPage.vue"),
        meta: { role: ["student"] },
      },
      {
        path: "/dashboard",
        name: "Dashboard",
        component: () =>
          import(/* webpackPreload: true */ "@/views/studentDashboard/defense/index2.vue"),
        meta: { role: ["student"] },
      },
      {
        path: "/question",
        name: "QuestionPage",
        component: () =>
          import(/* webpackPrefetch: true */ "@/views/studentDashboard/questions/index2.vue"),
        meta: { role: ["student"] },
      },
      {
        path: "/resultOver",
        name: "ResultOverPage",
        component: () =>
          import(/* webpackPrefetch: true */ "@/views/ResultOverPage.vue"),
        meta: { role: ["student"] },
      },
      {
        path: "/studentDashboard",
        name: "StudentDashboard",
        component: () =>
          import(
            /* webpackPrefetch: true */ "@/views/studentDashboard/index.vue"
          ),
        meta: { role: ["student"] },
      },
      {
        path: "/studyDetail",
        name: "StudentStudyDetail",
        component: () =>
          import(
            /* webpackPrefetch: true */ "@/views/studentDashboard/studyDetail/index.vue"
          ),
        meta: { role: ["student"] },
      },
    ],
  },
];

export const teacherRoutes = [
  {
    path: "/",
    component: Layout,
    redirect: "/teacherDashboard",
    children: [
      {
        path: "/prepared-paper/:assessmentId",
        name: "TeacherPreparedPaper",
        component: () => import("@/views/prepared/PreparedPaperPage.vue"),
        meta: { role: ["teacher"] },
      },
      {
        path: "/prepared-review/:assessmentId",
        name: "TeacherPreparedReview",
        component: () => import("@/views/prepared/PreparedReviewPage.vue"),
        meta: { role: ["teacher"] },
      },
      {
        path: "/teacherDashboard",
        name: "TeacherDashboard",
        component: () =>
          import(
            /* webpackPreload: true */ "@/views/teacherDashboard/index.vue"
          ),
        meta: { role: ["teacher"] },
      },
    ],
  },
  {
    path: "/studyDetail",
    name: "StudyDetail",
    component: () =>
      import(/* webpackPrefetch: true */ "@/views/studyDetail/index.vue"),
    meta: { role: ["teacher"] },
  },
];

export const adminRoutes = [
  {
    path: "/",
    component: Layout,
    redirect: "/adminDashboard",
    children: [
      {
        path: "/prepared-paper/:assessmentId",
        name: "AdminPreparedPaper",
        component: () => import("@/views/prepared/PreparedPaperPage.vue"),
        meta: { role: ["admin"] },
      },
      {
        path: "/prepared-review/:assessmentId",
        name: "AdminPreparedReview",
        component: () => import("@/views/prepared/PreparedReviewPage.vue"),
        meta: { role: ["admin"] },
      },
      {
        path: "/adminDashboard",
        name: "AdminDashboard",
        component: () =>
          import(/* webpackPreload: true */ "@/views/adminDashboard/home.vue"),
        meta: { role: ["admin"] },
      },
    ],
  },
  {
    path: "/studyDetail",
    name: "StudyDetail",
    component: () =>
      import(/* webpackPrefetch: true */ "@/views/studyDetail/index.vue"),
    meta: { role: ["admin"] },
  },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) {
      return savedPosition;
    }
    return { top: 0 };
  },
});

export default router;
