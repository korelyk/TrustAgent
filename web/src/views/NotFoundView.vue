<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <div class="page">
    <el-result icon="warning" title="404" sub-title="页面不存在">
      <template #extra>
        <RouterLink to="/apps">
          <el-button type="primary">返回应用列表</el-button>
        </RouterLink>
      </template>
    </el-result>
  </div>
</template>

<style scoped>
.page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
