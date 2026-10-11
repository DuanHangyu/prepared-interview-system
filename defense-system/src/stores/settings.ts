import { useDynamicTitle } from "@/utils/dynamicTitle";
import { defineStore } from "pinia";

const dynamicTitle = import.meta.env.VUE_APP_TITLE;

function loadStorageSetting(): { dynamicTitle?: string } {
  const stored = localStorage.getItem("layout-setting");
  if (!stored) {
    return {};
  }
  try {
    const parsed: unknown = JSON.parse(stored);
    return parsed !== null && typeof parsed === "object"
      ? (parsed as { dynamicTitle?: string })
      : {};
  } catch {
    localStorage.removeItem("layout-setting");
    return {};
  }
}

const storageSetting = loadStorageSetting();

const useSettingsStore = defineStore("settings", {
  state: () => ({
    title: "",
    dynamicTitle:
      storageSetting.dynamicTitle === undefined
        ? dynamicTitle
        : storageSetting.dynamicTitle,
  }),
  actions: {
    // 修改布局设置
    changeSetting(data: { key: string; value: string }) {
      const { key, value } = data;
      if (this.hasOwnProperty(key)) {
        this[key as 'title' | 'dynamicTitle'] = value;
      }
    },
    // 设置网页标题
    setTitle(title: string) {
      this.title = title;
      useDynamicTitle();
    },
  },
});

export default useSettingsStore;
