import { createI18n } from 'vue-i18n'

const zh = {
  nav: { dashboard: '概览', subscriptions: '订阅', consumers: '消费者', tokens: 'Token 管理', tasks: '任务', messages: '消息', logout: '退出登录' },
  login: {
    title: 'wxbot-admin 登录',
    hint: '粘贴服务端 application.yml 中 wx-sync.admin-ui-token 配置的固定 Token。',
    staticToken: '固定 Admin Token',
    connect: '连接',
    invalid: '令牌无效或服务端不可达',
  },
  common: {
    create: '新建', edit: '编辑', delete: '删除', revoke: '作废', cancel: '取消', confirm: '确定',
    save: '保存', search: '搜索', loading: '加载中', copy: '复制', copied: '已复制',
    total: '共 {n} 条', all: '全部', enabled: '启用', disabled: '停用', yes: '是', no: '否',
    showOnce: '凭据仅此一次展示，请立即复制保存',
    empty: '暂无数据',
  },
  dash: {
    title: '概览',
    accounts: '注册账号', consumers: '消费者', subscriptions: '订阅', sessions: '累计消息会话',
    tokenState: '登录令牌', tokenOk: '有效', tokenExpire: '约 {min} 分钟后过期', refreshNow: '刷新数据',
  },
  sub: {
    title: '订阅管理', create: '新建订阅', consumerId: '消费者', name: '名称',
    deliveryMode: '投递模式', webhookUrl: 'Webhook URL', filterJson: '过滤条件（JSON 对象）',
    visibleSeconds: '可见性租约(秒)', maxAttempts: '最大尝试', backoffBase: '退避基数(秒)',
    orderMode: '顺序模式', enabled: '状态', filter: '过滤条件', alreadyExists: '该订阅已存在（幂等返回）',
    createdOk: '订阅已创建', updatedOk: '订阅已更新', deletedOk: '订阅已删除',
    poll: 'POLL 拉取', webhook: 'WEBHOOK 推送',
  },
  cons: {
    title: '消费者管理', create: '新建消费者', name: '名称', enablePoll: '启用 POLL',
    enableWebhook: '启用 WEBHOOK', webhookUrl: 'Webhook URL', remark: '备注',
    pollToken: 'POLL Token', webhookSecret: 'Webhook Secret', createdOk: '消费者已创建',
    deletedOk: '消费者已删除',
  },
  tok: {
    title: 'Token 管理', accounts: '账号令牌', regCodes: '注册码',
    account: '账号', wxid: 'wxid', hostname: '主机', lastSeen: '最近心跳', createdAt: '创建时间',
    issueToken: '签发令牌', register: '注册账号', hostnameField: '主机名（可空）',
    ttl: '有效期', ttl7d: '7 天', ttl30d: '30 天', ttl90d: '90 天',
    tokenIssued: '令牌已签发（仅此一次展示）', accessToken: 'Access Token', refreshToken: 'Refresh Token',
    accountDeleted: '账号已删除', label: '备注', preBind: '预绑定账号（可空）',
    expiresHours: '有效小时数（缺省 24）', codeIssued: '注册码已签发（仅此一次展示）',
    regCode: '注册码', expiresAt: '过期时间', usedBy: '消费账号', usedAt: '消费时间',
    revokedOk: '注册码已作废', issuedAt: '签发时间',
  },
  col: {
    id: 'ID', actions: '操作', status: '状态', name: '名称', mode: '模式', value: '值', remark: '备注',
  },
  task: {
    title: '任务管理', type: '类型', target: '目标', attempts: '尝试', payload: '载荷',
    runs: '执行历史', runsTitle: '执行历史：任务 {id}', reset: '重置', cancelOk: '任务已取消',
    resetOk: '任务已重置回 PENDING', error: '错误', result: '结果',
  },
  msg: {
    title: '消息浏览', chatId: '会话 ID', displayName: '显示名', isGroup: '群聊',
    messageCount: '消息数', ofChat: '消息：{chat}', time: '时间', sender: '发送者',
    type: '类型', body: '内容', loadMore: '加载更多', noMore: '没有更多了',
  },
  err: { reqFail: '请求失败', connFail: '无法连接服务端', kicked: '令牌已失效，请重新登录' },
}

const en = {
  nav: { dashboard: 'Dashboard', subscriptions: 'Subscriptions', consumers: 'Consumers', tokens: 'Token Mgmt', tasks: 'Tasks', messages: 'Messages', logout: 'Sign out' },
  login: {
    title: 'wxbot-admin Sign in',
    hint: 'Paste the fixed token configured as wx-sync.admin-ui-token in the server application.yml.',
    staticToken: 'Fixed Admin Token',
    connect: 'Connect',
    invalid: 'Token invalid or server unreachable',
  },
  common: {
    create: 'Create', edit: 'Edit', delete: 'Delete', revoke: 'Revoke', cancel: 'Cancel', confirm: 'OK',
    save: 'Save', search: 'Search', loading: 'Loading', copy: 'Copy', copied: 'Copied',
    total: '{n} items', all: 'All', enabled: 'Enabled', disabled: 'Disabled', yes: 'Yes', no: 'No',
    showOnce: 'Shown once only — copy and store it now',
    empty: 'No data',
  },
  dash: {
    title: 'Dashboard',
    accounts: 'Accounts', consumers: 'Consumers', subscriptions: 'Subscriptions', sessions: 'Chat sessions',
    tokenState: 'Session token', tokenOk: 'Valid', tokenExpire: 'Expires in ~{min} min', refreshNow: 'Refresh data',
  },
  sub: {
    title: 'Subscriptions', create: 'New subscription', consumerId: 'Consumer', name: 'Name',
    deliveryMode: 'Delivery mode', webhookUrl: 'Webhook URL', filterJson: 'Filter (JSON object)',
    visibleSeconds: 'Visibility (s)', maxAttempts: 'Max attempts', backoffBase: 'Backoff base (s)',
    orderMode: 'Order mode', enabled: 'State', filter: 'Filter', alreadyExists: 'Already exists (idempotent)',
    createdOk: 'Subscription created', updatedOk: 'Subscription updated', deletedOk: 'Subscription deleted',
    poll: 'POLL pull', webhook: 'WEBHOOK push',
  },
  cons: {
    title: 'Consumers', create: 'New consumer', name: 'Name', enablePoll: 'Enable POLL',
    enableWebhook: 'Enable WEBHOOK', webhookUrl: 'Webhook URL', remark: 'Remark',
    pollToken: 'POLL Token', webhookSecret: 'Webhook Secret', createdOk: 'Consumer created',
    deletedOk: 'Consumer deleted',
  },
  tok: {
    title: 'Token Mgmt', accounts: 'Account tokens', regCodes: 'Reg codes',
    account: 'Account', wxid: 'wxid', hostname: 'Host', lastSeen: 'Last seen', createdAt: 'Created',
    issueToken: 'Issue token', register: 'Register account', hostnameField: 'Hostname (optional)',
    ttl: 'TTL', ttl7d: '7 days', ttl30d: '30 days', ttl90d: '90 days',
    tokenIssued: 'Token issued (shown once)', accessToken: 'Access Token', refreshToken: 'Refresh Token',
    accountDeleted: 'Account deleted', label: 'Label', preBind: 'Pre-bind account (optional)',
    expiresHours: 'Expires hours (default 24)', codeIssued: 'Reg code issued (shown once)',
    regCode: 'Reg code', expiresAt: 'Expires', usedBy: 'Used by', usedAt: 'Used at',
    revokedOk: 'Reg code revoked', issuedAt: 'Issued at',
  },
  col: {
    id: 'ID', actions: 'Actions', status: 'Status', name: 'Name', mode: 'Mode', value: 'Value', remark: 'Remark',
  },
  task: {
    title: 'Tasks', type: 'Type', target: 'Target', attempts: 'Attempts', payload: 'Payload',
    runs: 'Runs', runsTitle: 'Runs: task {id}', reset: 'Reset', cancelOk: 'Task cancelled',
    resetOk: 'Task reset to PENDING', error: 'Error', result: 'Result',
  },
  msg: {
    title: 'Messages', chatId: 'Chat ID', displayName: 'Display name', isGroup: 'Group',
    messageCount: 'Count', ofChat: 'Messages: {chat}', time: 'Time', sender: 'Sender',
    type: 'Type', body: 'Body', loadMore: 'Load more', noMore: 'No more',
  },
  err: { reqFail: 'Request failed', connFail: 'Server unreachable', kicked: 'Token expired — sign in again' },
}

export default createI18n({
  legacy: false,
  locale: 'zh-CN',
  fallbackLocale: 'zh-CN',
  messages: { 'zh-CN': zh, 'en-US': en },
})
