package com.wzx.babiq.server.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.conversation.repository.ProviderConfigRecord;
import com.wzx.babiq.server.persistence.entity.ProviderConfigEntity;
import com.wzx.babiq.server.persistence.mapper.ProviderConfigMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Provider 配置持久化服务。
 *
 * <p>该服务是 P2-3 设置系统的基础：它只保存 secretRef，不保存明文 API Key，从服务边界上限制误写密钥。</p>
 */
@Service
public class ProviderPersistenceService {

    /** Provider 配置单表 mapper。 */
    private final ProviderConfigMapper providerConfigMapper;

    /**
     * 创建 ProviderPersistenceService。
     *
     * @param providerConfigMapper Provider 配置 mapper
     */
    public ProviderPersistenceService(ProviderConfigMapper providerConfigMapper) {
        this.providerConfigMapper = providerConfigMapper;
    }

    /**
     * 保存或更新 Provider 配置。
     *
     * @param record Provider 配置领域记录
     */
    @Transactional
    public void saveProvider(ProviderConfigRecord record) {
        ProviderConfigEntity entity = toEntity(record);
        ProviderConfigEntity existing = providerConfigMapper.selectOne(Wrappers.<ProviderConfigEntity>lambdaQuery()
                .eq(ProviderConfigEntity::getProviderId, record.providerId()));
        if (existing == null) {
            providerConfigMapper.insert(entity);
            return;
        }
        entity.setId(existing.getId());
        providerConfigMapper.updateById(entity);
    }

    /**
     * 按 providerId 查询 Provider 配置。
     *
     * @param providerId Provider 标识
     * @return 找到时返回配置，否则为空
     */
    public Optional<ProviderConfigRecord> findProvider(String providerId) {
        return Optional.ofNullable(providerConfigMapper.selectOne(Wrappers.<ProviderConfigEntity>lambdaQuery()
                        .eq(ProviderConfigEntity::getProviderId, providerId)))
                .map(this::toRecord);
    }

    /**
     * 查询 Provider 配置列表。
     *
     * @param enabledOnly true 时只返回启用配置
     * @return 按更新时间倒序排列的 Provider 配置
     */
    public List<ProviderConfigRecord> listProviders(boolean enabledOnly) {
        var query = Wrappers.<ProviderConfigEntity>lambdaQuery()
                .orderByDesc(ProviderConfigEntity::getUpdatedAt);
        if (enabledOnly) {
            query.eq(ProviderConfigEntity::getEnabled, true);
        }
        return providerConfigMapper.selectList(query).stream()
                .map(this::toRecord)
                .toList();
    }

    /**
     * 软删除 Provider：保留配置和历史 turn，只把 enabled 置为 false。
     *
     * @param providerId Provider 标识
     * @param now 更新时间
     */
    @Transactional
    public void disableProvider(String providerId, Instant now) {
        ProviderConfigEntity existing = providerConfigMapper.selectOne(Wrappers.<ProviderConfigEntity>lambdaQuery()
                .eq(ProviderConfigEntity::getProviderId, providerId));
        if (existing == null) {
            return;
        }
        existing.setEnabled(false);
        existing.setUpdatedAt(PersistenceTime.write(now));
        providerConfigMapper.updateById(existing);
    }

    private ProviderConfigEntity toEntity(ProviderConfigRecord record) {
        ProviderConfigEntity entity = new ProviderConfigEntity();
        entity.setProviderId(record.providerId());
        entity.setDisplayName(record.displayName());
        entity.setType(record.type());
        entity.setBaseUrl(record.baseUrl());
        entity.setModel(record.model());
        entity.setSecretRef(record.secretRef());
        entity.setContextWindow(record.contextWindow());
        entity.setEnabled(record.enabled());
        entity.setCreatedAt(PersistenceTime.write(record.createdAt()));
        entity.setUpdatedAt(PersistenceTime.write(record.updatedAt()));
        return entity;
    }

    private ProviderConfigRecord toRecord(ProviderConfigEntity entity) {
        return new ProviderConfigRecord(entity.getProviderId(), entity.getDisplayName(), entity.getType(),
                entity.getBaseUrl(), entity.getModel(), entity.getSecretRef(), entity.getContextWindow(),
                Boolean.TRUE.equals(entity.getEnabled()), PersistenceTime.read(entity.getCreatedAt()),
                PersistenceTime.read(entity.getUpdatedAt()));
    }
}
