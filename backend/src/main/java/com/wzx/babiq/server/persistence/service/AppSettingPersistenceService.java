package com.wzx.babiq.server.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.conversation.repository.AppSettingRecord;
import com.wzx.babiq.server.persistence.entity.AppSettingEntity;
import com.wzx.babiq.server.persistence.mapper.AppSettingMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 应用设置持久化服务。
 *
 * <p>P2-1 只提供通用 key/value 读写。P2-3 会在上层增加类型化设置对象，让 UI 不直接处理裸字符串。</p>
 */
@Service
public class AppSettingPersistenceService {

    /** 设置表 mapper，负责 `bq_app_settings` 单表 CRUD。 */
    private final AppSettingMapper appSettingMapper;

    /**
     * 创建 AppSettingPersistenceService。
     *
     * @param appSettingMapper 设置表 mapper
     */
    public AppSettingPersistenceService(AppSettingMapper appSettingMapper) {
        this.appSettingMapper = appSettingMapper;
    }

    /**
     * 保存或更新设置项。
     *
     * @param record 设置领域记录
     */
    @Transactional
    public void save(AppSettingRecord record) {
        AppSettingEntity entity = toEntity(record);
        AppSettingEntity existing = appSettingMapper.selectOne(Wrappers.<AppSettingEntity>lambdaQuery()
                .eq(AppSettingEntity::getSettingKey, record.settingKey()));
        if (existing == null) {
            appSettingMapper.insert(entity);
            return;
        }
        entity.setId(existing.getId());
        appSettingMapper.updateById(entity);
    }

    /**
     * 按 key 查询设置。
     *
     * @param settingKey 设置 key
     * @return 找到时返回设置记录，否则为空
     */
    public Optional<AppSettingRecord> findByKey(String settingKey) {
        return Optional.ofNullable(appSettingMapper.selectOne(Wrappers.<AppSettingEntity>lambdaQuery()
                        .eq(AppSettingEntity::getSettingKey, settingKey)))
                .map(this::toRecord);
    }

    /**
     * 删除设置项；仅用于需要恢复“原先不存在”状态的事务补偿。
     *
     * @param settingKey 设置 key
     */
    @Transactional
    public void deleteByKey(String settingKey) {
        appSettingMapper.delete(Wrappers.<AppSettingEntity>lambdaQuery()
                .eq(AppSettingEntity::getSettingKey, settingKey));
    }

    private AppSettingEntity toEntity(AppSettingRecord record) {
        AppSettingEntity entity = new AppSettingEntity();
        entity.setSettingKey(record.settingKey());
        entity.setSettingValue(record.settingValue());
        entity.setValueType(record.valueType());
        entity.setUpdatedAt(PersistenceTime.write(record.updatedAt()));
        return entity;
    }

    private AppSettingRecord toRecord(AppSettingEntity entity) {
        return new AppSettingRecord(entity.getSettingKey(), entity.getSettingValue(), entity.getValueType(),
                PersistenceTime.read(entity.getUpdatedAt()));
    }
}
