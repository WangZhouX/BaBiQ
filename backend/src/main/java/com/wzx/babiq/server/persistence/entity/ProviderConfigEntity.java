package com.wzx.babiq.server.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * `bq_provider_configs` 表对应的 MyBatis-Plus 实体。
 *
 * <p>Provider 配置用于 P2-3 设置页和后端模型切换。安全边界是：这里永远只保存 secretRef，
 * 明文 API Key 必须交给 SecretStore，因此 `toString()` 也不会包含明文密钥。</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@TableName("bq_provider_configs")
public class ProviderConfigEntity {

    /** 数据库内部自增主键；不暴露给设置页。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** Provider 稳定标识，设置页和 turn 请求都用它引用配置。 */
    @TableField("provider_id")
    private String providerId;

    /** Provider 展示名称，例如 DeepSeek。 */
    @TableField("display_name")
    private String displayName;

    /** Provider 类型，用于选择后端模型客户端适配器。 */
    private String type;

    /** API Base URL。 */
    @TableField("base_url")
    private String baseUrl;

    /** 默认模型名。 */
    private String model;

    /** 密钥引用；只指向 SecretStore，不保存明文 API Key。 */
    @TableField("secret_ref")
    private String secretRef;

    /** 上下文窗口大小，用于 UI 展示和 prompt 预算。 */
    @TableField("context_window")
    private Integer contextWindow;

    /** 是否启用；SQLite 使用 0/1 保存，实体用 Boolean 表达业务语义。 */
    private Boolean enabled;

    /** 配置创建时间。 */
    @TableField("created_at")
    private String createdAt;

    /** 配置更新时间。 */
    @TableField("updated_at")
    private String updatedAt;
}
