<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import type { components } from '../../shared/api/generated/http';
import { api } from '../../shared/api/client';
import { setCsrf } from '../../shared/api/session';
import { useInteractionAnswer } from './use-interaction-answer';
import { useChatDrafts } from './drafts';

type PlanInteraction = Extract<components['schemas']['PendingInteraction'], { kind: 'PLAN_APPROVAL' }>;
const props = defineProps<{ interaction: PlanInteraction; actor: string; space: string; ready: boolean }>();
const emit = defineEmits<{ refresh: [] }>();
const editing = ref(false); const title = ref(''); const markdown = ref(''); const request = ref(''); const editError = ref(''); const saving = ref(false);
const drafts=useChatDrafts();const baseRevision=ref(0);
const plan = useQuery({ queryKey: computed(() => ['chat', props.actor, props.space, 'plan', props.interaction.taskId, props.interaction.interactionId]),
  queryFn: async () => (await api.GET('/api/v1/tasks/{id}/plan', { params: { path: { id: props.interaction.taskId } } })).data! });
const current = computed(() => props.interaction.plan);
const previous = computed(() => plan.data.value?.revisions.at(-2));
const toolExecution = computed(() => Boolean(plan.data.value?.executionPhase && plan.data.value.executionPhase !== 'TEXT_ONLY'));
const reply = useInteractionAnswer(() => props.interaction, () => { emit('refresh'); void plan.refetch(); });
watch(current,value=>{
  const saved=drafts.loadPlan(props.actor,props.space,props.interaction.taskId);
  if(saved){editing.value=true;title.value=saved.title;markdown.value=saved.markdown;baseRevision.value=saved.baseRevision;}
  else if(!editing.value){title.value=value?.title??'';markdown.value=value?.markdown??'';baseRevision.value=value?.revision??0;}
},{immediate:true});
watch([title,markdown,editing],()=>{if(editing.value)drafts.savePlan(props.actor,props.space,props.interaction.taskId,{title:title.value,markdown:markdown.value,baseRevision:baseRevision.value});},{flush:'sync'});
function answer(decision: components['schemas']['AnswerInteraction']['decision']) {
  void reply.send({ expectedVersion: props.interaction.version, decision, text: decision === 'REVISE_PLAN' ? request.value : '', optionIds: [] });
}
async function save() {
  if (!plan.data.value || saving.value || !props.ready) return;
  saving.value = true; editError.value = '';
  try {
    const token = (await api.GET('/api/v1/auth/csrf')).data!.csrfToken; setCsrf(token);
    await api.PUT('/api/v1/tasks/{id}/plan', { params: { path: { id: props.interaction.taskId },
      header: { 'If-Match': `"${plan.data.value.version}"`, 'X-CSRF-Token': token } }, body: { title: title.value, markdown: markdown.value } });
    editing.value = false;drafts.clearPlan(props.actor,props.space,props.interaction.taskId);emit('refresh');
  } catch { editError.value = '保存未确认或版本已改变。编辑内容保留，请核对最新修订后再保存。'; emit('refresh'); await plan.refetch(); }
  finally { saving.value = false; }
}
</script>

<template>
  <section class="interaction-card" aria-label="计划审阅" data-testid="plan-card">
    <h3>{{ current?.title ?? '计划审阅' }} <small>修订 {{ current?.revision }}</small></h3>
    <p>{{ toolExecution ? '确认后在同一任务按计划继续。确认不授予工作区权限，也不替代具体工具审批。' : '确认仅继续文本处理，不授予文件、终端或其他工具权限。' }}</p>
    <p v-if="!ready" role="status">正在保存等待状态，完成后即可审阅和编辑。</p>
    <p class="chat-text">{{ current?.markdown }}</p>
    <details v-if="previous"><summary>与上一修订比较</summary><div class="plan-comparison"><section><h4>上一修订</h4><pre>{{ previous.markdown }}</pre></section><section><h4>当前修订</h4><pre>{{ current?.markdown }}</pre></section></div></details>
    <p>审阅截止：{{ new Date(interaction.expiresAt).toLocaleString() }}</p>
    <p v-if="reply.error.value" role="alert">{{ reply.error.value }}</p>
    <button v-if="reply.uncertain.value" :disabled="reply.busy.value" @click="reply.retry">重试原回答</button>
    <fieldset :disabled="!ready || reply.busy.value || reply.uncertain.value || saving"><legend>处理当前计划</legend>
      <div class="chat-actions"><button @click="answer('EXECUTE_PLAN')">{{ toolExecution ? '按此执行' : '按此执行文本' }}</button><button @click="answer('DELIVER_PLAN')">仅交付方案</button><button @click="answer('REJECT_PLAN')">拒绝并继续讨论</button><button @click="editing = !editing">直接编辑计划</button></div>
      <label :for="`revision-request-${interaction.interactionId}`">希望模型修改的内容</label>
      <textarea :id="`revision-request-${interaction.interactionId}`" v-model="request" rows="3" />
      <button :disabled="!request.trim()" @click="answer('REVISE_PLAN')">提出修改</button>
    </fieldset>
    <form v-if="editing" @submit.prevent="save">
      <p v-if="baseRevision !== current?.revision" role="status">编辑草稿基于修订 {{ baseRevision }}，当前为 {{ current?.revision }}。请比较差异后决定是否保存新修订。</p>
      <label :for="`plan-title-${interaction.interactionId}`">计划标题</label><input :id="`plan-title-${interaction.interactionId}`" v-model="title" :disabled="saving" />
      <label :for="`plan-text-${interaction.interactionId}`">计划正文</label><textarea :id="`plan-text-${interaction.interactionId}`" v-model="markdown" rows="10" :disabled="saving" />
      <p v-if="editError" role="alert">{{ editError }}</p><button :disabled="!ready || saving || !title.trim() || !markdown.trim()">保存新修订</button>
    </form>
  </section>
</template>
