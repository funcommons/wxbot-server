package com.wechat.sync.server.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * 纯单测（不起 MyBatis 上下文）时手动注册实体 TableInfo：MyBatis Plus 3.5.15 的
 * {@code LambdaQueryWrapper.eq} / {@code LambdaUpdateWrapper.set} 在构建期就要解析列名，
 * 缺缓存直接抛 "can not find lambda cache for this entity"。
 */
public final class MpTestTables {

    private static final MapperBuilderAssistant ASSISTANT =
            new MapperBuilderAssistant(new MybatisConfiguration(), "");

    private MpTestTables() { }

    /** 幂等初始化（TableInfoHelper 内部已按 Class 缓存）。 */
    public static void init(Class<?>... entities) {
        for (Class<?> entity : entities) {
            if (TableInfoHelper.getTableInfo(entity) == null) {
                TableInfoHelper.initTableInfo(ASSISTANT, entity);
            }
        }
    }
}
