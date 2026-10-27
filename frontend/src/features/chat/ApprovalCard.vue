<script setup lang="ts">
import { computed } from 'vue';
import type { components } from '../../shared/api/generated/http';
import { useInteractionAnswer } from './use-interaction-answer';

type Approval = Extract<components['schemas']['PendingInteraction'], { kind: 'TOOL_APPROVAL' }>;
type Decision = 'ALLOW_ONCE' | 'DENY';
const props = defineProps<{ interaction: Approval; ready: boolean }>();
const emit = defineEmits<{ refresh: [] }>();
const approval = computed(() => props.interaction.approval);
const reply = useInteractionAnswer(() => props.interaction, () => emit('refresh'));
const canAllow = computed(() => !approval.value.stale && approval.value.allowedActions.includes('ALLOW_ONCE'));
const canDeny = computed(() => approval.value.allowedActions.includes('DENY'));
function answer(decision: Decision) {
  if (reply.accepted.value || decision === 'ALLOW_ONCE' && !canAllow.value || decision === 'DENY' && !canDeny.value) return;
  void reply.send({ expectedVersion: props.interaction.version, decision });
}
</script>

<template>
  <section class="interaction-card" aria-label="工具操作确认" data-testid="approval-card">
    <h3>确认当前操作</h3>
    <p class="chat-text">{{ approval.summary }}</p>
    <dl>
      <dt>工具</dt><dd>{{ approval.toolName }}</dd>
      <dt>需要确认的原因</dt><dd>{{ approval.reasonCode }}</dd>
      <template v-if="approval.target"><dt>目标</dt><dd>{{ approval.target }}</dd></template>
      <template v-if="approval.targetVersion"><dt>目标版本</dt><dd>{{ approval.targetVersion }}</dd></template>
    </dl>
    <p>只批准这一次原操作，不修改参数，不扩大目录授权或其他权限。</p>
    <p>有效期至：{{ new Date(interaction.expiresAt).toLocaleString() }}</p>
    <p v-if="approval.stale" role="status">操作或授权版本已改变，旧批准不可用。你可以拒绝本次操作，或取消任务。</p>
    <p v-if="!ready" role="status">正在保存等待状态并确认执行已停止，完成后即可处理。</p>
    <p v-if="reply.accepted.value" role="status">决定已接受，正在核对排队和执行状态。接受决定不代表操作已成功。</p>
    <p v-if="reply.error.value" role="alert">{{ reply.error.value }}</p>
    <button v-if="reply.uncertain.value" type="button" :disabled="reply.busy.value" @click="reply.retry">重试原决定</button>
    <fieldset :disabled="!ready || reply.busy.value || reply.uncertain.value || reply.accepted.value">
      <legend>仅处理当前操作</legend>
      <button v-if="canAllow" type="button" @click="answer('ALLOW_ONCE')">允许这一次</button>
      <button v-if="canDeny" type="button" @click="answer('DENY')">拒绝本次操作</button>
    </fieldset>
    <details><summary>核对操作标识</summary><dl>
      <dt>操作编号</dt><dd>{{ approval.operationId }}</dd>
      <dt>调用摘要</dt><dd>{{ approval.callHash }}</dd>
      <dt>权限修订</dt><dd>{{ approval.permissionVersion }}</dd>
      <dt>策略版本</dt><dd>{{ approval.policyVersion }}</dd>
    </dl></details>
  </section>
</template>

<style scoped>
section { overflow-wrap: anywhere; }
dl { margin: .75rem 0; }
dt { font-weight: 600; }
dd { margin: .15rem 0 .65rem; white-space: pre-wrap; }
fieldset { min-width: 0; display: flex; flex-wrap: wrap; gap: .75rem; }
</style>
