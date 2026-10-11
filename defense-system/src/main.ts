import { createApp } from "vue";
import App from "./App.vue";
import router from "./router";
import { createPinia } from "pinia";
import "./assets/styles/global.css";
import "ant-design-vue/dist/reset.css";
import Antd, { message } from "ant-design-vue";
import { setupLoadingDirective } from "./directives/loading";
import "@/utils/rem";
import "./router/permission";

const app = createApp(App);

message.config({
  duration: 3,
});
app.use(createPinia());
app.use(Antd);
app.use(router);

setupLoadingDirective(app);

app.mount("#app");

// // main.js
// window.addEventListener("load", function () {
//   // 禁用选择文本
//   document.body.onselectstart = () => false;

//   // 禁用右键菜单
//   document.oncontextmenu = () => false;

//   // 禁用拖拽
//   document.ondragstart = () => false;
// });

// // 禁止右键菜单
// document.addEventListener("contextmenu", function (e) {
//   e.preventDefault();
// });

// // 禁止文本选择
// document.addEventListener("selectstart", function (e) {
//   e.preventDefault();
// });

// // 禁止拖拽
// document.addEventListener("dragstart", function (e) {
//   e.preventDefault();
// });

// // 禁止键盘复制快捷键
// document.addEventListener("keydown", function (e) {
//   // 禁止 Ctrl+C, Ctrl+V, Ctrl+A, Ctrl+X
//   if (
//     e.ctrlKey &&
//     (e.keyCode === 67 ||
//       e.keyCode === 86 ||
//       e.keyCode === 65 ||
//       e.keyCode === 88)
//   ) {
//     e.preventDefault();
//   }
//   // 禁止 F12 开发者工具
//   if (e.keyCode === 123) {
//     e.preventDefault();
//   }
// });
