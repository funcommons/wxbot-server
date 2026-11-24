<template>
  <div class="app-page">
    <FcSectionHeader :title="t('contact.title')" />

    <FcTabsPanel v-model="tab" :tabs="tabs">
      <template #tab-contacts>
        <FcFilterBar>
          <FcInput v-model="kw" :placeholder="t('common.search')" clearable style="width: 200px"
                   @enter="cPage = 1; loadContacts()" />
          <FcSegmented v-model="source" :options="sourceOptions" @change="cPage = 1; loadContacts()" />
        </FcFilterBar>
        <FcSection>
          <FcTable :data="filteredContacts" :loading="loadingContacts" stripe>
            <el-table-column :label="t('contact.avatar')" width="70">
              <template #default="{ row }">
                <FcAvatar :src="row.big_head_url" :name="row.username" size="medium" />
              </template>
            </el-table-column>
            <el-table-column prop="username" :label="t('msg.chatId')" min-width="200" show-overflow-tooltip />
            <el-table-column prop="alias" :label="t('contact.alias')" width="130" show-overflow-tooltip />
            <el-table-column prop="nick_name" :label="t('contact.nickname')" min-width="140" />
            <el-table-column prop="remark" :label="t('contact.remark2')" min-width="140" />
            <el-table-column :label="t('contact.source')" width="120">
              <template #default="{ row }">
                <FcTag :color="row.source === 'STRANGER' ? 'warning' : 'primary'" size="sm">{{ row.source }}</FcTag>
              </template>
            </el-table-column>
            <el-table-column prop="contact_id" :label="t('contact.rowId')" width="140" />
          </FcTable>
          <FcPagination v-model:current-page="cPage" v-model:page-size="cPageSize" :total="cTotal"
                        style="margin-top: 12px; justify-content: flex-end" @change="loadContacts" />
        </FcSection>
      </template>

      <template #tab-groups>
        <FcSection>
          <FcTable :data="groupRows" :loading="loadingGroups" stripe
                   @row-click="(r: GroupRow) => openMembers(r)">
            <el-table-column prop="username" :label="t('msg.chatId')" min-width="220" show-overflow-tooltip />
            <el-table-column prop="display_name" :label="t('msg.displayName')" min-width="160" />
            <el-table-column prop="message_count" :label="t('msg.messageCount')" width="120" />
            <el-table-column :label="t('contact.members')" width="110">
              <template #default>
                <FcButton variant="text" size="sm">{{ t('contact.viewMembers') }}</FcButton>
              </template>
            </el-table-column>
          </FcTable>
          <FcPagination v-model:current-page="gPage" v-model:page-size="gPageSize" :total="gTotal"
                        style="margin-top: 12px; justify-content: flex-end" @change="loadGroups" />
        </FcSection>
      </template>
    </FcTabsPanel>

    <!-- 群成员抽屉 -->
    <FcDrawer v-model:open="mOpen" :title="t('contact.membersOf', { group: mGroup })" width="640px" append-to-body>
      <template v-if="mOpen">
        <FcTable :data="members" :loading="loadingMembers" stripe>
          <el-table-column prop="member_username" :label="t('msg.chatId')" min-width="200" show-overflow-tooltip />
          <el-table-column prop="nick_name" :label="t('contact.nickname')" min-width="140" />
          <el-table-column :label="t('contact.owner')" width="90">
            <template #default="{ row }">
              <FcTag v-if="row.is_owner" color="warning" size="sm">{{ t('contact.owner') }}</FcTag>
            </template>
          </el-table-column>
        </FcTable>
        <FcPagination v-model:current-page="mPage" v-model:page-size="mPageSize" :total="mTotal"
                      style="margin-top: 12px; justify-content: flex-end" @change="loadMembers" />
      </template>
    </FcDrawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { FcSectionHeader, FcSection, FcFilterBar, FcTable, FcTag, FcButton,
         FcPagination, FcDrawer, FcInput, FcSegmented, FcTabsPanel, FcAvatar } from '@/components/sdk'
import { get } from '@/api/client'
import type { PageVO } from '@/api/types'

interface ContactRow {
  username: string; source?: string; nick_name?: string; remark?: string; contact_id?: number
  alias?: string; big_head_url?: string
}
interface GroupRow { username: string; display_name?: string; message_count?: number }
interface MemberRow { member_username: string; nick_name?: string; is_owner?: boolean }

const { t } = useI18n()
const tab = ref('contacts')
const tabs = [
  { value: 'contacts', label: t('contact.tabContacts') },
  { value: 'groups', label: t('contact.tabGroups') },
]

// ---- 联系人 ----
const contactRows = ref<ContactRow[]>([])
const loadingContacts = ref(false)
const cPage = ref(1)
const cPageSize = ref(20)
const cTotal = ref(0)
const kw = ref('')
const source = ref('')

const sourceOptions = [
  { label: t('common.all'), value: '' },
  { label: 'CONTACT', value: 'CONTACT' },
  { label: 'STRANGER', value: 'STRANGER' },
]

const filteredContacts = computed(() =>
  kw.value ? contactRows.value.filter((r) => JSON.stringify(r).toLowerCase().includes(kw.value.toLowerCase())) : contactRows.value)

// ---- 群与成员 ----
const groupRows = ref<GroupRow[]>([])
const loadingGroups = ref(false)
const gPage = ref(1)
const gPageSize = ref(20)
const gTotal = ref(0)

const mOpen = ref(false)
const loadingMembers = ref(false)
const mGroup = ref('')
const members = ref<MemberRow[]>([])
const mPage = ref(1)
const mPageSize = ref(20)
const mTotal = ref(0)

async function loadContacts() {
  loadingContacts.value = true
  try {
    const q = new URLSearchParams({ page: String(cPage.value), pageSize: String(cPageSize.value) })
    if (source.value) q.set('source', source.value)
    const data = await get<PageVO<ContactRow>>(`/v1/contacts?${q}`)
    contactRows.value = data.list
    cTotal.value = data.total
  } catch {
    ElMessage.error(t('err.reqFail'))
  } finally {
    loadingContacts.value = false
  }
}

async function loadGroups() {
  loadingGroups.value = true
  try {
    const data = await get<PageVO<GroupRow>>(`/v1/chats?isGroup=true&page=${gPage.value}&pageSize=${gPageSize.value}`)
    groupRows.value = data.list
    gTotal.value = data.total
  } catch {
    ElMessage.error(t('err.reqFail'))
  } finally {
    loadingGroups.value = false
  }
}

function openMembers(row: GroupRow) {
  mGroup.value = row.username
  mPage.value = 1
  mOpen.value = true
  loadMembers()
}

async function loadMembers() {
  loadingMembers.value = true
  try {
    const data = await get<PageVO<MemberRow>>(
      `/v1/groups/${encodeURIComponent(mGroup.value)}/members?union=true&page=${mPage.value}&pageSize=${mPageSize.value}`)
    members.value = data.list
    mTotal.value = data.total
  } catch {
    ElMessage.error(t('err.reqFail'))
  } finally {
    loadingMembers.value = false
  }
}

onMounted(() => { loadContacts(); loadGroups() })
</script>
