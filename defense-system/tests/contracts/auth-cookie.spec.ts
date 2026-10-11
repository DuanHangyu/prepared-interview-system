import { describe, expect, it } from "vitest";
import authUtil from "../../src/utils/auth.ts?raw";
import authStore from "../../src/stores/auth.ts?raw";
import requestClient from "../../src/utils/request.ts?raw";
import questionsPage from "../../src/views/studentDashboard/questions/index2.vue?raw";
import defensePage from "../../src/views/studentDashboard/defense/index2.vue?raw";
import voicePlayer from "../../src/components/InterviewVoicePlayer.vue?raw";
import speechRecognition from "../../src/composables/useSpeechRecognition.js?raw";

describe("HttpOnly session cookie contract", () => {
  it("never stores or forwards the session credential from JavaScript", () => {
    expect(authUtil).not.toContain("getToken");
    expect(authUtil).not.toContain("setToken");
    expect(authStore).not.toContain("setToken(res.data)");
    expect(requestClient).not.toContain("headers.Authorization");
    expect(requestClient).toContain("withCredentials: true");
  });

  it("lets the browser authenticate WebSockets with the HttpOnly cookie", () => {
    for (const source of [
      questionsPage,
      defensePage,
      voicePlayer,
      speechRecognition,
    ]) {
      expect(source).not.toContain("getToken");
    }
  });
});
