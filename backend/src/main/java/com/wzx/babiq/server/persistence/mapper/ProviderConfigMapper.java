package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.ProviderConfigEntity;

/**
 * `bq_provider_configs` 的 MyBatis-Plus Mapper。
 *
 * <p>Provider 配置涉及密钥引用，业务层必须通过 service/repository 写入，避免绕开 secretRef 约束。</p>
 */
public interface ProviderConfigMapper extends BaseMapper<ProviderConfigEntity> {
}
