<script setup lang="ts">
import { ref } from 'vue';
import type { components } from '../../shared/api/generated/http';
import { useInteractionAnswer } from './use-interaction-answer';
const props = defineProps<{ interaction: components['schemas']['PendingInteraction'] }>();
const emit = defineEmits<{ refresh: [] }>();
const text = ref(''); const selected = ref<string[]>([]);
const reply = useInteractionAnswer(() => props.interaction, () => emit('refresh'));
function answer(skip = false) { void reply.send({ expectedVersion: props.interaction.version,
  decision: skip ? 'SKIP' : 'ANSWER', text: skip ? '' : text.value, optionIds: skip ? [] : selected.value }); }
</script>

<template>
  <section class="interaction-card" aria-label="补充信息" data-testid="question-card">
    <h3>补充信息 <small>{{ interaction.optional ? '可选，不阻塞执行' : '需要回答后继续' }}</small></h3>
    <p class="chat-text">{{ interaction.question }}</p>
    <p>有效期至：{{ new Date(interaction.expiresAt).toLocaleString() }}</p>
    <p v-if="reply.error.value" role="alert">{{ reply.error.value }}</p>
    <button v-if="reply.uncertain.value" :disabled="reply.busy.value" @click="reply.retry">重试原回答</button>
    <form @submit.prevent="answer()"><fieldset :disabled="reply.busy.value || reply.uncertain.value"><legend>你的回答</legend>
      <label v-for="option in interaction.options" :key="option.id"><input v-model="selected" type="checkbox" :value="option.id" /> {{ option.label }}</label>
      <label :for="`question-answer-${interaction.interactionId}`">补充说明</label><textarea :id="`question-answer-${interaction.interactionId}`" v-model="text" rows="3" />
      <div class="chat-actions"><button :disabled="!text.trim() && !selected.length">提交回答</button><button v-if="interaction.optional" type="button" @click="answer(true)">跳过</button></div>
    </fieldset></form>
  </section>
</template>
