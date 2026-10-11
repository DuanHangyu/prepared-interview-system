<template>
  <main class="console-layout">
    <div class="console-frame">
      <section class="console-content">
      <router-view v-slot="{ Component, route }">
        <keep-alive :include="['AdminDashboard', 'TeacherDashboard']">
          <component :is="Component" :key="route.path" />
        </keep-alive>
      </router-view>
      </section>
    </div>
  </main>
</template>
<script setup lang="ts">
defineOptions({
  name: "Layout",
});
</script>
<style scoped>
.console-layout {
  width: 100%;
  min-height: 100dvh;
  height: auto;
  padding: 20px 24px;
  overflow-x: hidden;
  overflow-y: auto;
  background:
    radial-gradient(circle, rgba(207, 218, 235, 0.42) 1px, transparent 1px),
    #f3f7fc;
  background-size: 28px 28px;
}

.console-frame {
  width: min(1680px, 100%);
  min-height: calc(100dvh - 40px);
  height: auto;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.console-content {
  min-height: 0;
  flex: 1;
  overflow: visible;
}

@media (max-width: 900px) {
  .console-layout {
    min-height: 100dvh;
    height: auto;
    padding: 16px;
    overflow: auto;
  }

  .console-frame {
    min-height: calc(100dvh - 28px);
    height: auto;
  }

  .console-content {
    overflow: visible;
  }
}

@media (max-width: 560px) {
  .console-layout {
    padding: 10px;
  }

  .console-frame {
    min-height: calc(100dvh - 20px);
    gap: 10px;
  }
}
</style>
