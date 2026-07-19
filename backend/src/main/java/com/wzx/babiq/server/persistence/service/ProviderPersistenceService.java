package com.wzx.babiq.server.persistence.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wzx.babiq.server.conversation.repository.ProviderConfigRecord;
import com.wzx.babiq.server.model.ProviderAuthMode;
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
        ProviderConfigEntity existing = providerConfigMapper.selectOne(Wrappers.<ProviderConfigEntity>lambdaQuery()
                .eq(ProviderConfigEntity::getProviderId, record.providerId()));
        if (existing == null) {
            insertProvider(record);
            return;
        }
        updateProvider(record);
    }

    /**
     * 只插入一条新的 Provider 配置，不执行 upsert。
     *
     * <p>设置页 create 必须使用这个入口，让数据库唯一约束和上层 create-if-absent 校验共同阻止
     * 重复 Provider 静默覆盖；兼容启动同步仍可继续调用 {@link #saveProvider(ProviderConfigRecord)}。</p>
     *
     * @param record 已通过设置服务校验、只包含 secretRef 的 Provider 配置
     */
    @Transactional
    public void insertProvider(ProviderConfigRecord record) {
        providerConfigMapper.insert(toEntity(record));
    }

    /**
     * 只更新已经存在的 Provider 配置。
     *
     * <p>该方法先按稳定 providerId 找到数据库主键，再执行按 id 更新；记录不存在时拒绝继续，
     * 避免设置页 update 意外退化成 create。</p>
     *
     * @param record 已通过设置服务校验、只包含 secretRef 的 Provider 配置
     */
    @Transactional
    public void updateProvider(ProviderConfigRecord record) {
        ProviderConfigEntity existing = requireExisting(record.providerId());
        ProviderConfigEntity entity = toEntity(record);
        entity.setId(existing.getId());
        // MyBatis-Plus 默认跳过 null 字段；切到 OAuth 时必须显式把 secret_ref 更新为 NULL。
        entity.setSecretRef(null);
        providerConfigMapper.update(entity, Wrappers.<ProviderConfigEntity>lambdaUpdate()
                .eq(ProviderConfigEntity::getId, existing.getId())
                .set(ProviderConfigEntity::getSecretRef, record.secretRef()));
    }

    /**
     * 物理删除刚刚插入但尚未对外成功的 Provider，仅供 create 提交后副作用失败时补偿。
     *
     * <p>正常业务删除仍必须走 settings service 的软删除语义；该入口不得用于用户发起的删除。</p>
     *
     * @param providerId 已提交但需要撤销的 Provider 标识
     */
    @Transactional
    public void hardDeleteProviderForCompensation(String providerId) {
        providerConfigMapper.delete(Wrappers.<ProviderConfigEntity>lambdaQuery()
                .eq(ProviderConfigEntity::getProviderId, providerId));
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

    /**
     * 返回必须存在的 Provider Entity，供显式 update 保持“只更新”语义。
     *
     * @param providerId Provider 稳定标识
     * @return 已存在的数据库实体
     */
    private ProviderConfigEntity requireExisting(String providerId) {
        ProviderConfigEntity existing = providerConfigMapper.selectOne(Wrappers.<ProviderConfigEntity>lambdaQuery()
                .eq(ProviderConfigEntity::getProviderId, providerId));
        if (existing == null) {
            throw new IllegalArgumentException("Provider 不存在: " + providerId);
        }
        return existing;
    }

    private ProviderConfigEntity toEntity(ProviderConfigRecord record) {
        ProviderConfigEntity entity = new ProviderConfigEntity();
        entity.setProviderId(record.providerId());
        entity.setDisplayName(record.displayName());
        entity.setType(record.type());
        entity.setAuthMode(ProviderAuthMode.fromWireValue(record.authMode()).wireValue());
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
                ProviderAuthMode.fromWireValue(entity.getAuthMode()).wireValue(),
                entity.getBaseUrl(), entity.getModel(), entity.getSecretRef(), entity.getContextWindow(),
                Boolean.TRUE.equals(entity.getEnabled()), PersistenceTime.read(entity.getCreatedAt()),
                PersistenceTime.read(entity.getUpdatedAt()));
    }
}
