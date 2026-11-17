package com.wechat.sync.server.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * wx_reg_code 行：一次性自助注册码——库只存 sha256 不存明文（TASK_DISPATCH §2.1）。
 *
 * <p>明文只在签发响应出现一次；消费即 {@code usedAt} 打点（一次性）；管理员作废 = 软删。
 * 部分唯一索引 {@code uk_wx_reg_code_hash} 仅约束 {@code isDeleted=0}（软删后同哈希可再签）。
 * account 为预绑定值：非 NULL 时注册请求的 account 必须与之一致（不一致 10402）。
 */
@Data
@TableName("wx_reg_code")
public class WxRegCodeEntity {

    /** 雪花 ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** sha256(明文码)（is_deleted=0 内唯一） */
    private String codeHash;

    /** 预绑定账号；NULL = 任意未注册账号可用（客户端携带本地数据目录名注册） */
    private String account;

    /** 备注（发给谁） */
    private String label;

    /** 过期即废（签发时缺省 +24h，服务层算） */
    private OffsetDateTime expiresAt;

    /** 非 NULL = 已消费（一次性） */
    private OffsetDateTime usedAt;

    /** 消费方 hostname（审计） */
    private String usedBy;

    /** 软删（管理员作废）：0 正常 / 1 已删除 */
    private Integer isDeleted;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
