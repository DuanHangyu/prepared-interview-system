import useSettingsStore from "@/stores/settings";

/**
 * 动态修改标题
 */
export function useDynamicTitle() {
  const defaultTitle = import.meta.env.VUE_APP_TITLE;
  const settingsStore = useSettingsStore();
  if (settingsStore.dynamicTitle) {
    document.title = settingsStore.title + " - " + defaultTitle;
  } else {
    document.title = defaultTitle;
  }
}
