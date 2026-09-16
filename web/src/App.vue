<script setup lang="ts">
import { RouterView } from 'vue-router'
</script>

<template>
  <!--
    顶层只放一个 RouterView。布局（侧边栏 / 顶栏）放在 AppLayout 里，
    登录页不走布局，因此布局不能写在 App.vue 上。
  -->
  <RouterView />
</template>

<style>
html,
body,
#app {
  margin: 0;
  padding: 0;
  height: 100%;
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', 'Microsoft YaHei', Arial, sans-serif;
}

/* 统一让长文本可以换行并保持可读：本项目的很多字段是 prompt / 模型输出 / JSON 片段 */
.mono {
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 12px;
}

.wrap-anywhere {
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
