package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.AppSettingEntity;

/**
 * `bq_app_settings` 的 MyBatis-Plus Mapper。
 *
 * <p>设置项采用 key/value 结构，具体 key 的默认值和校验由 P2-3 设置服务负责。</p>
 */
public interface AppSettingMapper extends BaseMapper<AppSettingEntity> {
}
