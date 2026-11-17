package com.wechat.sync.server.relation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wechat.sync.server.relation.dto.RelationsIngestVO;
import com.wechat.sync.server.relation.dto.RelationsReportRequest;
import com.wechat.sync.server.relation.entity.WxContactEntity;
import com.wechat.sync.server.relation.entity.WxConversationEntity;
import com.wechat.sync.server.relation.entity.WxGroupEntity;
import com.wechat.sync.server.relation.entity.WxGroupMemberRelEntity;
import com.wechat.sync.server.relation.entity.WxLabelDictEntity;
import com.wechat.sync.server.relation.entity.WxLabelMemberHintEntity;
import com.wechat.sync.server.relation.mapper.WxContactMapper;
import com.wechat.sync.server.relation.mapper.WxConversationMapper;
import com.wechat.sync.server.relation.mapper.WxGroupMapper;
import com.wechat.sync.server.relation.mapper.WxGroupMemberRelMapper;
import com.wechat.sync.server.relation.mapper.WxLabelDictMapper;
import com.wechat.sync.server.relation.mapper.WxLabelMemberHintMapper;
import com.wechat.sync.server.relation.support.IntArrayTypeHandler;
import com.wechat.sync.server.support.MpTestTables;
import fun.commons.framework4j.web.ApiException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Captor;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.BDDMockito.given;

/** 关系层 ingest：六类 upsert、校验次序（10101→10102）、标签映射 tag_ids/hint 三分支。 */
@ExtendWith(MockitoExtension.class)
class RelationsIngestServiceTest {

    private static final String ACCOUNT = "wxid_a_0001";

    @Mock private WxContactMapper contactMapper;
    @Mock private WxGroupMapper groupMapper;
    @Mock private WxGroupMemberRelMapper groupMemberMapper;
    @Mock private WxLabelDictMapper labelDictMapper;
    @Mock private WxLabelMemberHintMapper labelHintMapper;
    @Mock private WxConversationMapper conversationMapper;

    @Captor private ArgumentCaptor<WxContactEntity> contactInsert;
    @Captor private ArgumentCaptor<LambdaUpdateWrapper<WxContactEntity>> contactUpdate;
    @Captor private ArgumentCaptor<WxGroupEntity> groupInsert;
    @Captor private ArgumentCaptor<LambdaUpdateWrapper<WxGroupEntity>> groupUpdate;
    @Captor private ArgumentCaptor<WxGroupMemberRelEntity> memberInsert;
    @Captor private ArgumentCaptor<LambdaUpdateWrapper<WxGroupMemberRelEntity>> memberUpdate;
    @Captor private ArgumentCaptor<LambdaQueryWrapper<WxLabelDictEntity>> labelLookup;
    @Captor private ArgumentCaptor<WxLabelDictEntity> labelInsert;
    @Captor private ArgumentCaptor<LambdaUpdateWrapper<WxLabelDictEntity>> labelUpdate;
    @Captor private ArgumentCaptor<WxLabelMemberHintEntity> hintInsert;
    @Captor private ArgumentCaptor<LambdaUpdateWrapper<WxLabelMemberHintEntity>> hintUpdate;
    @Captor private ArgumentCaptor<WxConversationEntity> conversationInsert;
    @Captor private ArgumentCaptor<LambdaUpdateWrapper<WxConversationEntity>> conversationUpdate;

    private RelationsIngestService service;

    @BeforeAll
    static void initTableInfo() {
        MpTestTables.init(WxContactEntity.class, WxGroupEntity.class, WxGroupMemberRelEntity.class,
                WxLabelDictEntity.class, WxLabelMemberHintEntity.class, WxConversationEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new RelationsIngestService(contactMapper, groupMapper, groupMemberMapper,
                labelDictMapper, labelHintMapper, conversationMapper);
    }

    private RelationsReportRequest.ContactItem contact(String username, String source, String contactId) {
        return new RelationsReportRequest.ContactItem(username, source, contactId, "nick", "remark",
                "alias", 1, Boolean.TRUE, 0, "http://head", "desc");
    }

    private RelationsReportRequest.GroupItem group(String username) {
        return new RelationsReportRequest.GroupItem(username, "77", "owner", "Room One", 3);
    }

    private RelationsReportRequest.GroupMemberItem member(String username, Boolean owner) {
        return new RelationsReportRequest.GroupMemberItem("room@chatroom", username, "张三", null, owner);
    }

    private RelationsReportRequest.LabelItem label(String name, String source) {
        return new RelationsReportRequest.LabelItem(name, 1, source, 2);
    }

    private RelationsReportRequest.LabelMemberItem labelMember(String name, String hint, String username,
                                                               Boolean ambiguous) {
        return new RelationsReportRequest.LabelMemberItem(name, hint, username, ambiguous);
    }

    private RelationsReportRequest.ConversationItem conversation(String username, Boolean isGroup) {
        return new RelationsReportRequest.ConversationItem(username, isGroup, "Me",
                OffsetDateTime.parse("2026-11-09T10:15:00+08:00"), 12L, 3);
    }

    /** 六个小节按位置传，缺省小节补 null（sections 尾部可省略）。 */
    @SuppressWarnings("unchecked")
    private RelationsReportRequest req(String account, List<?>... sections) {
        Object[] s = new Object[6];
        System.arraycopy(sections, 0, s, 0, Math.min(sections.length, 6));
        return new RelationsReportRequest(account, (List<RelationsReportRequest.ContactItem>) s[0],
                (List<RelationsReportRequest.GroupItem>) s[1],
                (List<RelationsReportRequest.GroupMemberItem>) s[2],
                (List<RelationsReportRequest.LabelItem>) s[3],
                (List<RelationsReportRequest.LabelMemberItem>) s[4],
                (List<RelationsReportRequest.ConversationItem>) s[5]);
    }

    private WxLabelDictEntity dict(int id, String tagValue) {
        WxLabelDictEntity e = new WxLabelDictEntity();
        e.setId(id);
        e.setAccount(ACCOUNT);
        e.setTagValue(tagValue);
        return e;
    }

    private WxContactEntity contactRow(long id) {
        WxContactEntity e = new WxContactEntity();
        e.setId(id);
        e.setAccount(ACCOUNT);
        e.setUsername("wxid_a_0002");
        e.setIsDeleted(0);
        return e;
    }

    /** 断言 ingest 抛出业务码（10101=account 缺失 / 10102=元素级缺失或越界）并返回异常消息。 */
    private String ingestCode(int expected, RelationsReportRequest req) {
        Throwable t = catchThrowable(() -> service.ingest(req));
        assertThat(t).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo(expected));
        return t.getMessage();
    }

    private void nothingWritten() {
        verifyNoInteractions(contactMapper, groupMapper, groupMemberMapper,
                labelDictMapper, labelHintMapper, conversationMapper);
    }

    @Test
    void requireAccountRejectsBlankWith10101AndTrims() {
        assertThat(service.requireAccount("  " + ACCOUNT + "  ")).isEqualTo(ACCOUNT);
        for (String blank : new String[]{null, "   ", ""}) {
            assertThat(catchThrowable(() -> service.requireAccount(blank)))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(10101));
        }
    }

    /** 次序：account 缺失 10101 先于元素级 10102；校验失败即刻中断事务、零落库；全空载荷计数全 0。 */
    @Test
    void ingestValidationOrderThenZeroCountsOnEmptyPayload() {
        ingestCode(10101, req(null, List.of(contact("   ", null, null))));
        nothingWritten();

        assertThat(ingestCode(10102, req(ACCOUNT, List.of(contact("  ", null, null)), List.of(group("room@chatroom")))))
                .contains("contacts[0].username");
        nothingWritten();

        RelationsIngestVO vo = service.ingest(req("  " + ACCOUNT + "  "));
        assertThat(vo.account()).isEqualTo(ACCOUNT);
        assertThat(vo.upserted()).isEqualTo(new RelationsIngestVO.Upserted(0, 0, 0, 0, 0, 0));
        nothingWritten();
    }

    /** contacts 新增与校验：username/source 归一化、rowid 解析 BIGINT、软删位复位、不碰 tag_ids。 */
    @Test
    void contactInsertNormalizesAndRejectsBadSourceOrRowId() {
        assertThat(ingestCode(10102, req(ACCOUNT, List.of(contact("wxid_b_0002", "robot", "42")))))
                .contains("contacts[0].source");
        assertThat(ingestCode(10102, req(ACCOUNT, List.of(contact("wxid_b_0002", "contact", "12x")))))
                .contains("contacts[0].contact_id");
        verify(contactMapper, never()).selectOne(any());

        given(contactMapper.selectOne(any())).willReturn(null);
        var vo = service.ingest(req(ACCOUNT, List.of(contact("  wxid_b_0002  ", " stranger ", " 12345 "))));

        assertThat(vo.upserted().contacts()).isEqualTo(1);
        verify(contactMapper).insert(contactInsert.capture());
        assertThat(contactInsert.getValue()).extracting(WxContactEntity::getAccount, WxContactEntity::getUsername,
                        WxContactEntity::getSource, WxContactEntity::getContactId, WxContactEntity::getNickName,
                        WxContactEntity::getLocalType, WxContactEntity::getIsDeleted)
                .containsExactly(ACCOUNT, "wxid_b_0002", "STRANGER", 12345L, "nick", 1, 0);
        assertThat(contactInsert.getValue().getIsInChatRoom()).isTrue();
        assertThat(contactInsert.getValue().getTagIds()).isNull();
    }

    /** contacts 已存在 → update 覆盖业务字段、保留 id，SET 列表绝不带 tag_ids；0 行命中不计数。 */
    @Test
    void contactUpdateExistingKeepsIdAndNeverTouchesTagIds() {
        WxContactEntity existing = contactRow(9L);
        existing.setTagIds(new int[]{7});
        given(contactMapper.selectOne(any())).willReturn(existing, contactRow(9L));
        given(contactMapper.update(isNull(), any())).willReturn(1, 0);

        assertThat(service.ingest(req(ACCOUNT, List.of(contact("wxid_a_0002", "contact", "42"))))
                .upserted().contacts()).isEqualTo(1);
        assertThat(service.ingest(req(ACCOUNT, List.of(contact("wxid_a_0002", null, null))))
                .upserted().contacts()).isZero();

        verify(contactMapper, times(2)).update(isNull(), contactUpdate.capture());
        // 第一次 update（命中 1 行）：SET 覆盖全部业务字段，绝不含 tag_ids；eq(id)=9 保留原主键
        LambdaUpdateWrapper<WxContactEntity> hit = contactUpdate.getAllValues().get(0);
        assertThat(hit.getSqlSet())
                .contains("source=", "contact_id=", "nick_name=", "remark=", "alias=", "local_type=",
                        "is_in_chat_room=", "delete_flag=", "big_head_url=", "description=", "updated_at=")
                .doesNotContain("tag_ids");
        hit.getSqlSegment(); // MP 3.5.15: WHERE 参数懒求值，先渲染
        assertThat(hit.getParamNameValuePairs()).containsValue(9L).containsValue("CONTACT").containsValue(42L);
        verify(contactMapper, never()).insert(any(WxContactEntity.class));
    }

    /** 部分更新语义：载荷缺席的业务字段不得进 SET（C++ 客户端只带子集；10106 回归 + 防既有行被抹）。 */
    @Test
    void contactUpdateSkipsAbsentFieldsFromSetClause() {
        WxContactEntity existing = contactRow(11L);
        given(contactMapper.selectOne(any())).willReturn(existing);
        given(contactMapper.update(isNull(), any())).willReturn(1);

        // wxbot-helper（C++）真实载荷形态：仅 username/source/nick_name/remark/alias，其余缺席
        RelationsReportRequest.ContactItem cppShape = new RelationsReportRequest.ContactItem(
                "wxid_a_0002", "contact", null, "nick", "remark", "alias", null, null, null, null, null);
        assertThat(service.ingest(req(ACCOUNT, List.of(cppShape))).upserted().contacts()).isEqualTo(1);

        verify(contactMapper).update(isNull(), contactUpdate.capture());
        String sqlSet = contactUpdate.getValue().getSqlSet();
        assertThat(sqlSet)
                .contains("source=", "nick_name=", "remark=", "alias=", "updated_at=")
                .doesNotContain("is_in_chat_room", "contact_id", "local_type",
                        "delete_flag", "big_head_url", "description", "tag_ids");
    }

    /** insert 兜底：is_in_chat_room 缺席时按 username 形态推导（@chatroom→true，否则 false），不炸 NOT NULL。 */
    @Test
    void contactInsertDerivesIsInChatRoomWhenAbsent() {
        given(contactMapper.selectOne(any())).willReturn(null, null);

        RelationsReportRequest.ContactItem room = new RelationsReportRequest.ContactItem(
                "123@chatroom", "contact", null, null, null, null, null, null, null, null, null);
        RelationsReportRequest.ContactItem plain = new RelationsReportRequest.ContactItem(
                "wxid_c_0003", null, null, null, null, null, null, null, null, null, null);
        var vo = service.ingest(req(ACCOUNT, List.of(room, plain)));

        assertThat(vo.upserted().contacts()).isEqualTo(2);
        verify(contactMapper, times(2)).insert(contactInsert.capture());
        assertThat(contactInsert.getAllValues().get(0).getIsInChatRoom()).isTrue();
        assertThat(contactInsert.getAllValues().get(1).getIsInChatRoom()).isFalse();
    }

    @Test
    void groupUpsertInsertsNewAndUpdatesExisting() {
        WxGroupEntity existing = new WxGroupEntity();
        existing.setId(5L);
        given(groupMapper.selectOne(any())).willReturn(null, existing);
        given(groupMapper.update(isNull(), any())).willReturn(1);

        var vo = service.ingest(req(ACCOUNT, null, List.of(group("  room1@chatroom  "), group("room2@chatroom"))));

        assertThat(vo.upserted().groups()).isEqualTo(2);
        verify(groupMapper).insert(groupInsert.capture());
        assertThat(groupInsert.getValue()).extracting(WxGroupEntity::getUsername, WxGroupEntity::getRoomId,
                WxGroupEntity::getIsDeleted).containsExactly("room1@chatroom", 77L, 0);
        verify(groupMapper).update(isNull(), groupUpdate.capture());
        assertThat(groupUpdate.getValue().getSqlSet())
                .contains("room_id=", "owner_username=", "display_name=", "member_count=", "updated_at=");
        verify(contactMapper, never()).insert(any(WxContactEntity.class));
    }

    /** group_members 三元素：username 缺失跳过（C10 不报错、不查库）、新成员 insert、已有覆盖 is_owner。 */
    @Test
    void groupMembersSkipUnresolvedUpsertNewAndOverwriteOwner() {
        WxGroupMemberRelEntity existing = new WxGroupMemberRelEntity();
        existing.setId(3L);
        given(groupMemberMapper.selectOne(any())).willReturn(null, existing);

        var vo = service.ingest(req(ACCOUNT, null, null,
                List.of(member("   ", null), member(" wxid_c_0003 ", null), member("wxid_c_0004", Boolean.TRUE))));

        assertThat(vo.upserted().groupMembers()).isEqualTo(2);
        verify(groupMemberMapper, times(2)).selectOne(any());
        verify(groupMemberMapper).insert(memberInsert.capture());
        assertThat(memberInsert.getValue().getMemberUsername()).isEqualTo("wxid_c_0003");
        assertThat(memberInsert.getValue().getIsOwner()).isFalse();
        verify(groupMemberMapper).update(isNull(), memberUpdate.capture());
        // member() 的 remark=null（缺席）→ 部分更新语义不进 SET；is_owner 恒写（Boolean 归一）
        assertThat(memberUpdate.getValue().getSqlSet()).contains("nick_name=", "is_owner=")
                .doesNotContain("remark");
        assertThat(memberUpdate.getValue().getParamNameValuePairs()).containsValue(Boolean.TRUE);
    }

    /** labels 新建：SERIAL 主键 keygen 未回填 id → 兜底再 findLabel 一次（同 tag_value 查两次）。 */
    @Test
    void labelInsertWithMissingKeygenFallsBackToLookup() {
        given(labelDictMapper.selectOne(any())).willReturn(null, dict(11, "friends"));
        doAnswer(inv -> 1).when(labelDictMapper) // MP insert 返回 int，返回实体会拆箱 CCE；id 保持 null 触发兜底回读
                .insert(any(WxLabelDictEntity.class));

        var vo = service.ingest(req(ACCOUNT, null, null, null, List.of(label("  friends  ", null))));

        assertThat(vo.upserted().labels()).isEqualTo(1);
        verify(labelDictMapper, times(2)).selectOne(labelLookup.capture());
        assertThat(labelLookup.getAllValues()).hasSize(2).allSatisfy(w -> {
            w.getSqlSegment(); // WHERE 参数懒求值，渲染后才进 paramNameValuePairs
            assertThat(w.getParamNameValuePairs()).containsValue(ACCOUNT).containsValue("friends");
        });
        verify(labelDictMapper).insert(labelInsert.capture());
        assertThat(labelInsert.getValue()).extracting(WxLabelDictEntity::getTagValue,
                        WxLabelDictEntity::getSource, WxLabelDictEntity::getSortOrder, WxLabelDictEntity::getId)
                .containsExactly("friends", "DB", 1, null);
    }

    /** labels 已有 → update 覆盖且 keygen 回填 id 后免回读；source 越界 / label_name 缺失 → 10102。 */
    @Test
    void labelUpsertExistingUpdatesKeygenSkipsRereadAndValidates() {
        given(labelDictMapper.selectOne(any())).willReturn(dict(7, "family"), (WxLabelDictEntity) null);
        doAnswer(inv -> {
            inv.getArgument(0, WxLabelDictEntity.class).setId(12);
            return 1;
        }).when(labelDictMapper).insert(any(WxLabelDictEntity.class));

        assertThat(service.ingest(req(ACCOUNT, null, null, null,
                List.of(label("family", "db"), label("work", "ui")))).upserted().labels()).isEqualTo(2);
        // 两个 label 各 1 次存在性 lookup（family 命中走 update 免回读；work 新建但 keygen 回填 id 后免回读）
        verify(labelDictMapper, times(2)).selectOne(any());
        verify(labelDictMapper).update(isNull(), labelUpdate.capture());
        assertThat(labelUpdate.getValue().getSqlSet())
                .contains("sort_order=", "source=", "member_count=", "updated_at=");
        assertThat(labelUpdate.getValue().getParamNameValuePairs()).containsValue("DB");
        verify(labelDictMapper).insert(labelInsert.capture());
        assertThat(labelInsert.getValue().getSource()).isEqualTo("UI");

        assertThat(ingestCode(10102, req(ACCOUNT, null, null, null, List.of(label("  ", null)))))
                .contains("labels[0].label_name");
        assertThat(ingestCode(10102, req(ACCOUNT, null, null, null, List.of(label("family", "weixin")))))
                .contains("labels[0].source");
    }

    /** label_members 三分支：唯一命中 → 只写 tag_ids 不进 hint 表；查无此人 → 新建 hint；已有 hint → update。 */
    @Test
    void labelMembersRouteToTagIdsNewHintAndExistingHint() {
        given(labelDictMapper.selectOne(any())).willReturn(dict(7, "friends"));
        given(contactMapper.appendTagId(ACCOUNT, "wxid_d_0004", 7)).willReturn(1);
        given(contactMapper.appendTagId(ACCOUNT, "wxid_ghost", 7)).willReturn(0);
        given(labelHintMapper.selectOne(any())).willReturn(null, new WxLabelMemberHintEntity());

        var vo = service.ingest(req(ACCOUNT, null, null, null, null, List.of(
                labelMember("  friends  ", "Bob", "  wxid_d_0004  ", null),
                labelMember("friends", "Bob", "wxid_ghost", null),
                labelMember("friends", "Bob", "wxid_ghost", Boolean.TRUE))));

        assertThat(vo.upserted().labelMembers()).isEqualTo(3);
        verify(contactMapper).appendTagId(ACCOUNT, "wxid_d_0004", 7);       // 分支一：唯一命中
        verify(contactMapper, times(2)).appendTagId(ACCOUNT, "wxid_ghost", 7);   // 分支二/三：查无此人
        verify(labelHintMapper).insert(hintInsert.capture());
        assertThat(hintInsert.getValue()).extracting(WxLabelMemberHintEntity::getDictId,
                        WxLabelMemberHintEntity::getMemberHint, WxLabelMemberHintEntity::getMemberUsername,
                        WxLabelMemberHintEntity::getIsAmbiguous)
                .containsExactly(7, "Bob", "wxid_ghost", true);              // 未解析 → 本端置位 ambiguous
        verify(labelHintMapper).update(isNull(), hintUpdate.capture());
        assertThat(hintUpdate.getValue().getSqlSet()).contains("member_username=", "is_ambiguous=")
                .doesNotContain("member_hint=");
        assertThat(hintUpdate.getValue().getParamNameValuePairs())
                .containsValue("wxid_ghost").containsValue(Boolean.TRUE);
        verify(contactMapper, never()).insert(any(WxContactEntity.class));
    }

    /** label_members 校验：字典查无且未上报 → 10102；命中不了联系人且 hint 为空 → 10102。 */
    @Test
    void labelMemberRequiresKnownLabelAndResolvableHint() {
        given(labelDictMapper.selectOne(any())).willReturn(null, dict(7, "family"));
        given(contactMapper.appendTagId(ACCOUNT, "wxid_e_0005", 7)).willReturn(0);

        // F13 修复后 field 路径已拼进 message（框架 ApiException 无 errorDetail getter，
        // error[].field 的信封翻译在 GlobalExceptionHandler 内部完成）
        assertThat(ingestCode(10102, req(ACCOUNT, null, null, null, null,
                List.of(labelMember("ghost-tag", "Bob", "wxid_e_0005", null)))))
                .contains("label_members[0].label_name").contains("ghost-tag");
        assertThat(ingestCode(10102, req(ACCOUNT, null, null, null, null,
                List.of(labelMember("family", "   ", "wxid_e_0005", null)))))
                .contains("member_hint");
        verifyNoInteractions(labelHintMapper);
        verify(contactMapper, times(1)).appendTagId(anyString(), anyString(), any());
    }

    @Test
    void conversationUpsertInsertsNewAndUpdatesExisting() {
        WxConversationEntity existing = new WxConversationEntity();
        existing.setId(4L);
        given(conversationMapper.selectOne(any())).willReturn(null, existing);
        given(conversationMapper.update(isNull(), any())).willReturn(1);

        var vo = service.ingest(req(ACCOUNT, null, null, null, null, null,
                List.of(conversation("wxid_a_0002", null), conversation("room@chatroom", Boolean.TRUE))));

        assertThat(vo.upserted().conversations()).isEqualTo(2);
        verify(conversationMapper).insert(conversationInsert.capture());
        assertThat(conversationInsert.getValue()).extracting(WxConversationEntity::getUsername,
                        WxConversationEntity::getIsGroup, WxConversationEntity::getLastMessageAt,
                        WxConversationEntity::getIsDeleted)
                .containsExactly("wxid_a_0002", false, OffsetDateTime.parse("2026-11-09T10:15:00+08:00"), 0);
        verify(conversationMapper).update(isNull(), conversationUpdate.capture());
        assertThat(conversationUpdate.getValue().getSqlSet()).contains("is_group=", "display_name=",
                "last_message_at=", "message_count=", "unread=", "updated_at=");
        assertThat(conversationUpdate.getValue().getParamNameValuePairs()).containsValue(Boolean.TRUE);
    }

    /** 六类各一行：响应计数逐一对应（label_members 走 tag_ids 命中，hint 表不落行）。 */
    @Test
    void ingestAggregatesCountsAcrossAllSixSections() {
        given(contactMapper.selectOne(any())).willReturn(null);
        given(groupMapper.selectOne(any())).willReturn(null);
        given(groupMemberMapper.selectOne(any())).willReturn(null);
        given(labelDictMapper.selectOne(any())).willReturn(dict(7, "friends"));
        given(contactMapper.appendTagId(ACCOUNT, "wxid_d_0004", 7)).willReturn(1);
        given(conversationMapper.selectOne(any())).willReturn(null);
        doAnswer(inv -> 1).when(contactMapper).insert(any(WxContactEntity.class));
        doAnswer(inv -> 1).when(groupMapper).insert(any(WxGroupEntity.class));
        doAnswer(inv -> 1).when(groupMemberMapper).insert(any(WxGroupMemberRelEntity.class));
        lenient().doAnswer(inv -> 1).when(labelDictMapper).insert(any(WxLabelDictEntity.class)); // label 已存在走 update，insert stub 不会被触达
        doAnswer(inv -> 1).when(conversationMapper).insert(any(WxConversationEntity.class));

        var vo = service.ingest(req(ACCOUNT, List.of(contact("wxid_a_0002", null, null)),
                List.of(group("room@chatroom")), List.of(member("wxid_c_0003", null)),
                List.of(label("friends", null)), List.of(labelMember("friends", "Bob", "wxid_d_0004", null)),
                List.of(conversation("wxid_a_0002", null))));

        assertThat(vo.account()).isEqualTo(ACCOUNT);
        assertThat(vo.upserted()).isEqualTo(new RelationsIngestVO.Upserted(1, 1, 1, 1, 1, 1));
        verifyNoInteractions(labelHintMapper);
    }

    /** tag_ids 只挂 int4[] TypeHandler 并依赖 autoResultMap（否则读侧三形状收敛不生效）。 */
    @Test
    void contactTagIdsDeclaresIntArrayTypeHandler() throws NoSuchFieldException {
        assertThat(WxContactEntity.class.getDeclaredField("tagIds")
                .getAnnotation(com.baomidou.mybatisplus.annotation.TableField.class)
                .typeHandler()).isEqualTo(IntArrayTypeHandler.class);
        assertThat(WxContactEntity.class.getAnnotation(
                com.baomidou.mybatisplus.annotation.TableName.class).autoResultMap()).isTrue();
    }
}
